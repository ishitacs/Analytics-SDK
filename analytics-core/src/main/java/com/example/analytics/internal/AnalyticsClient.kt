package com.example.analytics.internal

import android.app.Application
import android.content.Context
import com.example.analytics.AnalyticsConfig
import com.example.analytics.AnalyticsEvent
import com.example.analytics.AnalyticsListener
import com.example.analytics.Destination
import com.example.analytics.DropReason
import com.example.analytics.EventPriority
import com.example.analytics.EventType
import com.example.analytics.remote.RemoteConfigManager
import com.example.analytics.remote.Sampling
import com.example.analytics.storage.DatabaseFiles
import com.example.analytics.storage.EventStore
import com.example.analytics.storage.MetaStore
import com.example.analytics.storage.PendingEvent
import com.example.analytics.storage.QueueDatabase
import com.example.analytics.storage.ResilientEventStore
import com.example.analytics.storage.SqliteEventStore
import com.example.analytics.upload.DrainResult
import com.example.analytics.upload.UploadScheduler
import com.example.analytics.upload.Uploader
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal enum class FlushReason { INIT, TIMER, BATCH_FULL, FOREGROUND, BACKGROUND, CONNECTIVITY, MANUAL }

/**
 * The per-process engine behind [com.example.analytics.Analytics].
 *
 * ```
 * caller thread ──trySend──► commands channel ──► writer (single thread):
 *                                enrich → remote rules (kill switch, block, sample) → route to destinations → SQLite
 *                                                     │ batch full / timer / background / network back
 *                                                     ▼
 *                                upload loop ──► per destination: Uploader.drain(store), own backoff
 * ```
 * Nothing here runs on the caller's thread except the non-blocking [enqueue].
 */
internal class AnalyticsClient(
    private val context: Context,
    val config: AnalyticsConfig,
    private val clock: Clock = Clock.System,
    private val processInfoProvider: () -> ProcessInfo = { ProcessInfo.current(context) },
    private val storeFactory: (Context, String, String, AnalyticsConfig) -> EventStore = ::defaultStore,
) {
    private val logger = config.logger
    private val listener = config.listener?.let { SafeListener(it, config) }
    private val destinations = config.allDestinations

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> logger.error("Analytics internal error", e) },
    )
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val writerDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val commands = Channel<Command>(COMMAND_BUFFER)
    private val flushRequests = Channel<FlushReason>(16, BufferOverflow.DROP_OLDEST)
    private val uploadMutex = Mutex()

    // Everything below is created lazily on background threads: no disk I/O on the caller's thread.
    private val process by lazy(processInfoProvider)
    private val queueName by lazy { DatabaseFiles.queueFor(process.name, context.packageName) }
    private val stores: Map<String, EventStore> by lazy {
        destinations.associate { it.id to storeFactory(context, queueName, it.id, config) }
    }
    private val meta by lazy { MetaStore(context) }
    private val identityStore by lazy { IdentityStore(meta, clock) }
    private val sequence by lazy { SequenceGenerator(meta) }
    private val sessions by lazy {
        SessionManager(meta, config.sessionTimeout.inWholeMilliseconds, config.newSessionOnColdStart, clock)
    }
    private val device by lazy { DeviceContext.create(context, process.name) }
    private val scheduler by lazy { UploadScheduler(context, logger) }
    private val remote = RemoteConfigManager(
        url = config.remoteConfigUrl,
        destination = destinations.first(),
        defaultRefreshMillis = config.remoteConfigRefreshInterval.inWholeMilliseconds,
        meta = { meta },
        clock = clock,
        logger = logger,
    )
    private val sanitizer = PropertySanitizer(config.propertyLimits, logger)
    private val envelopeBuilder = EnvelopeBuilder(config.propertyLimits.maxEventBytes)
    private val connectivity = ConnectivityMonitor(context, logger) { requestFlush(FlushReason.CONNECTIVITY) }
    private val uploaders: Map<String, Uploader> = destinations.associate { destination ->
        destination.id to Uploader(
            destination = destination,
            eventTtlMillis = config.eventTtl.inWholeMilliseconds,
            maxAttempts = config.maxAttempts,
            logger = logger,
            clock = clock,
            onUploaded = { count -> listener?.let { it.onBatchUploaded(destination.id, count, safeCount(destination.id)) } },
            onDropped = { count, reason -> listener?.onEventsDropped(count, reason) },
            onFailed = { count, error -> listener?.onUploadFailed(destination.id, count, error, willRetry = true) },
        )
    }

    /** An event ready to persist, with the user-level view destination filters look at. */
    private class Routed(val pending: PendingEvent, val event: AnalyticsEvent)

    private class BackoffState {
        var consecutiveFailures = 0
        var backoffUntil = 0L
        var lastFailureWasNetwork = false
    }

    // Writer-thread state.
    private val superProperties = LinkedHashMap<String, Any?>()
    private var fallbackIdentity: Identity? = null
    private var fallbackSeq = 0L

    // Read by both writer and upload loop.
    @Volatile private var optedOut = false
    @Volatile private var optOutLoaded = false
    @Volatile private var optOutCheckedAt = 0L
    private val unflushed = AtomicInteger()
    @Volatile private var queueMayHaveEvents = true

    fun start() {
        scope.launch(writerDispatcher) {
            removeUnconfiguredDestinations()
            runWriter()
        }
        scope.launch { runUploadLoop() }
        scope.launch {
            connectivity.start()
            if (process.isMainProcess && config.backgroundUploads) scheduler.schedulePeriodic()
            requestFlush(FlushReason.INIT)
        }
        (context.applicationContext as? Application)?.let { app ->
            AppLifecycleTracker(
                onForeground = {
                    enqueue(Command.AppForeground(clock.currentTimeMillis()))
                    requestFlush(FlushReason.FOREGROUND)
                },
                onBackground = {
                    enqueue(Command.AppBackground(clock.currentTimeMillis()))
                    onAppBackgrounded()
                },
            ).register(app)
        }
    }

    /** Never blocks. */
    fun enqueue(command: Command) {
        if (!commands.trySend(command).isSuccess && command is Command.Track) {
            listener?.onEventsDropped(1, DropReason.BUFFER_FULL)
        }
    }

    fun requestFlush(reason: FlushReason) {
        flushRequests.trySend(reason)
    }

    /** Fetch remote config now, regardless of its refresh schedule. */
    fun refreshRemoteConfig() {
        scope.launch {
            remote.refreshIfDue(force = true)
            requestFlush(FlushReason.MANUAL)
        }
    }

    /** Used by the WorkManager job. @return true if every queue was fully drained. */
    suspend fun flushAllQueues(): Boolean = withContext(Dispatchers.IO) {
        if (isOptedOut()) return@withContext true
        if (process.isMainProcess) remote.refreshIfDue()
        // Uploads switched off remotely: nothing to retry now; the periodic job checks again later.
        if (!remote.current().uploadsEnabled) return@withContext true

        var allDone = uploadMutex.withLock {
            unflushed.set(0)
            // map before all(): one failing destination must not stop the others from draining.
            destinations.map { drain(it, stores.getValue(it.id)) }.all { it is DrainResult.Done }
        }
        DatabaseFiles.allQueues(context).filter { it != queueName }.forEach { name ->
            destinations.forEach { destination ->
                val other = storeFactory(context, name, destination.id, config)
                try {
                    if (drain(destination, other) !is DrainResult.Done) allDone = false
                } finally {
                    other.close()
                }
            }
        }
        allDone
    }

    /** Test hook: stops background work. */
    fun shutdown() {
        scope.cancel()
    }

    private fun onAppBackgrounded() {
        requestFlush(FlushReason.BACKGROUND)
        scope.launch {
            if (process.isMainProcess && config.backgroundUploads) scheduler.scheduleOneTime()
        }
    }

    private fun removeUnconfiguredDestinations() {
        try {
            val removed = QueueDatabase.open(context, queueName).deleteDestinationsExcept(destinations.map { it.id })
            if (removed > 0) logger.warn("Discarded $removed events queued for destinations no longer configured")
        } catch (e: Exception) {
            logger.warn("Could not clean up removed destinations", e)
        }
    }

    // ---------------------------------------------------------------- writer

    private suspend fun runWriter() {
        for (first in commands) {
            val pending = ArrayList<Routed>()
            var command: Command? = first
            var processed = 0
            var flushRequested = false
            // Drain whatever else is queued so a burst becomes one SQLite transaction per destination.
            while (command != null) {
                try {
                    if (command is Command.Flush) flushRequested = true else process(command, pending)
                } catch (e: Exception) {
                    logger.error("Failed to process ${command.javaClass.simpleName}", e)
                }
                command = if (++processed < MAX_COMMANDS_PER_WRITE) commands.tryReceive().getOrNull() else null
            }
            if (pending.isNotEmpty()) persist(pending)
            if (flushRequested) requestFlush(FlushReason.MANUAL)
        }
    }

    private fun process(command: Command, pending: MutableList<Routed>) {
        when (command) {
            is Command.Track -> {
                sessionEvent { sessions.onEvent(command.clientTs) }?.let { pending += it }
                buildEvent(command)?.let { pending += it }
            }
            is Command.Identify -> identify(command, pending)
            is Command.Reset -> {
                identityStore.reset()
                superProperties.clear()
                sessionEvent { sessions.rotate(command.clientTs, "reset") }?.let { pending += it }
            }
            is Command.AppForeground ->
                sessionEvent { sessions.onForeground(command.clientTs, process.isMainProcess) }?.let { pending += it }
            is Command.AppBackground -> sessionEvent {
                sessions.onBackground(command.clientTs)
                null
            }
            is Command.SetSuperProperties -> superProperties.putAll(command.properties)
            is Command.RemoveSuperProperty -> superProperties.remove(command.key)
            is Command.SetOptOut -> {
                setOptOut(command.optOut)
                if (command.optOut) pending.clear()
            }
            is Command.Flush -> Unit // handled in runWriter
        }
    }

    private fun identify(command: Command.Identify, pending: MutableList<Routed>) {
        val previousUserId = currentIdentity().userId
        identityStore.identify(command.userId)
        // Anonymous -> logged in is the same person: keep the session. Switching users is not.
        if (previousUserId != null && previousUserId != command.userId) {
            sessionEvent { sessions.rotate(command.clientTs, "identity_change") }?.let { pending += it }
        } else {
            sessionEvent { sessions.onEvent(command.clientTs) }?.let { pending += it }
        }
        val event = Command.Track(
            name = "identify",
            type = EventType.IDENTIFY,
            properties = command.traits,
            priority = EventPriority.HIGH,
            clientTs = command.clientTs,
            elapsedRealtime = command.elapsedRealtime,
        )
        buildEvent(event)?.let { pending += it }
    }

    /** Runs a session transition; if it started a session, returns the `session_start` event. */
    private inline fun sessionEvent(transition: () -> SessionStart?): Routed? {
        val start = try {
            transition()
        } catch (e: Exception) {
            logger.warn("Session store unavailable", e)
            null
        } ?: return null
        val event = Command.Track(
            name = "session_start",
            type = EventType.SYSTEM,
            properties = mapOf("previous_session_id" to start.previousSessionId, "reason" to start.reason),
            priority = EventPriority.HIGH,
            clientTs = clock.currentTimeMillis(),
            elapsedRealtime = clock.elapsedRealtime(),
        )
        return buildEvent(event)
    }

    private fun buildEvent(command: Command.Track): Routed? {
        if (isOptedOut()) return null
        var event = AnalyticsEvent(command.name, command.type, superProperties + command.properties)
        config.beforeSend?.let { hook ->
            event = try {
                hook(event)
            } catch (e: Exception) {
                logger.error("beforeSend threw; sending event unmodified", e)
                event
            } ?: return drop(DropReason.FILTERED)
        }
        val name = sanitizer.sanitizeName(event.name) ?: return drop(DropReason.INVALID)
        val identity = currentIdentity()

        // Remote rules. SDK events (sessions, identify) can't be blocked or sampled per-event, only
        // by the global kill switch / device sample, so sessions stay consistent with what's sent.
        val rules = remote.current()
        if (!rules.trackingEnabled) return drop(DropReason.REMOTE_CONFIG)
        val sdkEvent = event.type == EventType.SYSTEM || event.type == EventType.IDENTIFY
        if (!sdkEvent && rules.isBlocked(name)) return drop(DropReason.REMOTE_CONFIG)
        val sampleRate = if (sdkEvent) rules.sampleRate else rules.sampleRateFor(name)
        if (!Sampling.isSampledIn(identity.deviceId, sampleRate)) return drop(DropReason.REMOTE_CONFIG)

        val eventId = UUID.randomUUID().toString()
        val json = envelopeBuilder.build(
            eventId = eventId,
            name = name,
            type = event.type,
            properties = sanitizer.sanitize(event.properties),
            identity = identity,
            sessionId = sessions.currentSessionId,
            seq = nextSeq(),
            clientTs = command.clientTs,
            elapsedRealtime = command.elapsedRealtime,
            device = device,
            networkType = connectivity.networkType,
            sampleRate = if (remote.isEnabled) sampleRate else null,
        ) ?: run {
            logger.warn("Event '$name' exceeds ${config.propertyLimits.maxEventBytes} bytes; dropped")
            return drop(DropReason.INVALID)
        }
        return Routed(PendingEvent(eventId, json, command.priority.value, command.clientTs), event.copy(name = name))
    }

    private fun persist(routed: List<Routed>) {
        var total = 0
        for (destination in destinations) {
            val events = routed.filter { accepts(destination, it.event) }
            val store = stores.getValue(destination.id)
            if (events.isNotEmpty()) store.insert(events.map { it.pending })

            val size = store.count()
            total += size
            if (size > config.maxQueueSize) {
                val evicted = store.trimTo(config.maxQueueSize)
                if (evicted > 0) {
                    logger.warn("Queue for '${destination.id}' full; evicted $evicted oldest events")
                    listener?.onEventsDropped(evicted, DropReason.QUEUE_FULL)
                    recordDrop(store, evicted, DropReason.QUEUE_FULL)
                }
            }
        }
        queueMayHaveEvents = true
        val unflushedCount = unflushed.addAndGet(routed.size)
        listener?.onEventsQueued(total)
        if (unflushedCount >= destinations.minOf { batchSizeFor(it) }) requestFlush(FlushReason.BATCH_FULL)
    }

    private fun accepts(destination: Destination, event: AnalyticsEvent): Boolean {
        val filter = destination.filter ?: return true
        return try {
            filter(event)
        } catch (e: Exception) {
            logger.error("Filter of destination '${destination.id}' threw; sending event anyway", e)
            true
        }
    }

    /** Makes data loss visible downstream instead of a silent gap. */
    private fun recordDrop(store: EventStore, count: Int, reason: DropReason) {
        val command = Command.Track(
            name = "sdk_events_dropped",
            type = EventType.SYSTEM,
            properties = mapOf("count" to count, "reason" to reason.name.lowercase()),
            priority = EventPriority.HIGH,
            clientTs = clock.currentTimeMillis(),
            elapsedRealtime = clock.elapsedRealtime(),
        )
        buildEvent(command)?.let { store.insert(listOf(it.pending)) }
    }

    private fun drop(reason: DropReason): Routed? {
        listener?.onEventsDropped(1, reason)
        return null
    }

    private fun currentIdentity(): Identity = try {
        identityStore.current()
    } catch (e: Exception) {
        logger.warn("Identity store unavailable; using a temporary identity", e)
        fallbackIdentity ?: Identity(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null)
            .also { fallbackIdentity = it }
    }

    private fun nextSeq(): Long = try {
        sequence.next()
    } catch (e: Exception) {
        // Negative = "not from the shared counter"; still unique enough for ordering within the process.
        -(++fallbackSeq)
    }

    private fun isOptedOut(): Boolean {
        val now = clock.elapsedRealtime()
        if (!optOutLoaded || now - optOutCheckedAt >= OPT_OUT_REFRESH_MILLIS) {
            optedOut = try {
                meta.get(KEY_OPT_OUT) == "1"
            } catch (e: Exception) {
                optedOut
            }
            optOutCheckedAt = now
            optOutLoaded = true
        }
        return optedOut
    }

    private fun setOptOut(optOut: Boolean) {
        try {
            meta.put(KEY_OPT_OUT, if (optOut) "1" else null)
        } catch (e: Exception) {
            logger.error("Could not persist opt-out", e)
        }
        optedOut = optOut
        optOutCheckedAt = clock.elapsedRealtime()
        optOutLoaded = true
        if (!optOut) return
        var purged = 0
        stores.values.forEach {
            purged += it.count()
            it.clear()
        }
        DatabaseFiles.allQueues(context).filter { it != queueName }.forEach { name ->
            try {
                QueueDatabase.open(context, name).clearAll()
            } catch (e: Exception) {
                logger.error("Could not purge $name on opt-out", e)
            }
        }
        if (purged > 0) listener?.onEventsDropped(purged, DropReason.OPTED_OUT)
    }

    // ---------------------------------------------------------------- upload loop

    private suspend fun runUploadLoop() {
        val backoff = destinations.associate { it.id to BackoffState() }

        while (scope.isActive) {
            val now = clock.elapsedRealtime()
            val interval = remote.current().flushIntervalMillis ?: config.flushInterval.inWholeMilliseconds
            val nextRetry = backoff.values.filter { it.backoffUntil > now }.minOfOrNull { it.backoffUntil - now }
            val wait = minOf(interval, nextRetry ?: Long.MAX_VALUE)
            val reasons = HashSet<FlushReason>()
            reasons += withTimeoutOrNull(wait) { flushRequests.receive() } ?: FlushReason.TIMER
            while (true) reasons += flushRequests.tryReceive().getOrNull() ?: break

            if (process.isMainProcess) remote.refreshIfDue()
            val manual = FlushReason.MANUAL in reasons
            // Known offline: don't burn battery; the connectivity callback will wake us.
            if (connectivity.isConnected == false && !manual) continue
            val idle = backoff.values.all { it.consecutiveFailures == 0 }
            if (reasons == setOf(FlushReason.TIMER) && !queueMayHaveEvents && idle) continue
            if (isOptedOut()) continue
            // Remote kill switch for sending (e.g. ingestion outage): events stay queued on device.
            if (!remote.current().uploadsEnabled) continue

            uploadMutex.withLock {
                withContext(Dispatchers.IO) {
                    // Cleared before draining: events written during the drain set it again.
                    queueMayHaveEvents = false
                    unflushed.set(0)
                    for (destination in destinations) {
                        val state = backoff.getValue(destination.id)
                        val inBackoff = clock.elapsedRealtime() < state.backoffUntil
                        val bypass = manual || (FlushReason.CONNECTIVITY in reasons && state.lastFailureWasNetwork)
                        if (inBackoff && !bypass) {
                            queueMayHaveEvents = true
                            continue
                        }
                        when (val result = drain(destination, stores.getValue(destination.id))) {
                            is DrainResult.Done -> {
                                state.consecutiveFailures = 0
                                state.backoffUntil = 0
                            }
                            is DrainResult.Retry -> {
                                queueMayHaveEvents = true
                                state.consecutiveFailures++
                                state.lastFailureWasNetwork = result.networkError
                                val delay = destination.retryPolicy.delayMillis(state.consecutiveFailures, result.retryAfterMillis)
                                state.backoffUntil = clock.elapsedRealtime() + delay
                                logger.debug("Upload to '${destination.id}' failed (${state.consecutiveFailures} in a row); retrying in ${delay}ms")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun drain(destination: Destination, store: EventStore): DrainResult =
        uploaders.getValue(destination.id).drain(store, batchSizeFor(destination))

    /** Remote config can resize the default destination's batches at runtime. */
    private fun batchSizeFor(destination: Destination): Int =
        if (destination.id == Destination.DEFAULT_ID) remote.current().batchSize ?: destination.batchSize
        else destination.batchSize

    private fun safeCount(destinationId: String): Int = try {
        stores.getValue(destinationId).count()
    } catch (_: Exception) {
        -1
    }

    /** Shields the pipeline from exceptions thrown by host-supplied listener code. */
    private class SafeListener(private val delegate: AnalyticsListener, private val config: AnalyticsConfig) :
        AnalyticsListener {
        override fun onEventsQueued(queueSize: Int) = guard { delegate.onEventsQueued(queueSize) }
        override fun onBatchUploaded(destination: String, eventCount: Int, queueSize: Int) =
            guard { delegate.onBatchUploaded(destination, eventCount, queueSize) }
        override fun onUploadFailed(destination: String, eventCount: Int, error: String, willRetry: Boolean) =
            guard { delegate.onUploadFailed(destination, eventCount, error, willRetry) }
        override fun onEventsDropped(count: Int, reason: DropReason) = guard { delegate.onEventsDropped(count, reason) }

        private inline fun guard(block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                config.logger.error("AnalyticsListener threw", e)
            }
        }
    }

    private companion object {
        const val COMMAND_BUFFER = 10_000
        const val MAX_COMMANDS_PER_WRITE = 200
        const val OPT_OUT_REFRESH_MILLIS = 5_000L
        const val KEY_OPT_OUT = "opt_out"

        fun defaultStore(context: Context, databaseName: String, destination: String, config: AnalyticsConfig): EventStore {
            val database = QueueDatabase.open(context, databaseName)
            return ResilientEventStore(
                openPrimary = { SqliteEventStore(database, destination) },
                deletePrimary = { database.deleteFile() },
                logger = config.logger,
            )
        }
    }
}
