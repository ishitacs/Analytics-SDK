# AnalyticsLib

An Android analytics SDK that records events to an on-device SQLite queue and delivers them in batches to your own HTTP backend. It is built to keep data through process death, offline periods and backend outages.

| Module | What it is |
|---|---|
| `:analytics-core` | The SDK: public `Analytics` API, queue, upload pipeline, sessions, identity, remote config |
| `:analytics-compose` | Jetpack Compose helpers for screen views, impressions and clicks |
| `:analytics-views` | The same helpers for classic Views, Fragments and RecyclerView |
| `:app` | Sample app with an in-process mock backend and a debug panel |

**Requirements:** minSdk 23, compileSdk 37. Core depends only on kotlinx-coroutines and WorkManager.

## Features

- **Never blocks or throws.** Every call is non-blocking from any thread. Calls made before `init` are buffered (up to 1,000) and replayed.
- **Durable queue.** Events are written to SQLite (WAL) and survive process kills and restarts. Each process has its own queue file, so multi-process apps are supported.
- **Background delivery.** Uploads happen on a timer, when a batch fills, when the app is backgrounded and when the network returns. WorkManager delivers what's left after the process dies.
- **Failure handling.** Exponential backoff with `Retry-After` support. A 413 splits the batch. A 4xx bisects the batch so only the bad event is dropped. Events expire after a TTL or a maximum number of attempts.
- **Sessions and identity.** Sessions end after 30 minutes in the background and rotate on user switch or reset. `device_id`, `anonymous_id` and `user_id` are shared across processes.
- **Safe payloads.** Property values are sanitized: depth, size and length limits, no NaN, and SDK-owned keys (`user_id`, `event_id`, …) can't be overwritten.
- **Multiple destinations.** Send events to more than one backend, each with its own filter, headers and retry state.
- **Remote config.** Kill switches for tracking and uploads, per-device and per-event sampling, blocked events, and batch-size and flush-interval overrides.
- **Privacy.** `optOut(true)` persists across launches and purges every queued event. `beforeSend` lets you scrub or drop events.

## Quick start

Initialize in `Application.onCreate`. This must run in every process that tracks events, and in the main process so background uploads can find the config.

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Analytics.init(
            this,
            AnalyticsConfig("https://api.myapp.com/v1/events") {
                header("X-App-Key", BuildConfig.ANALYTICS_KEY)
                headerProvider = { mapOf("Authorization" to "Bearer ${tokens.current()}") } // evaluated per request
                batchSize = 50
                flushInterval = 30.seconds
                remoteConfigUrl = "https://api.myapp.com/v1/analytics-config"
            },
        )
    }
}
```

Track events:

```kotlin
Analytics.track("purchase", mapOf("sku" to "A1", "price" to 9.99), EventPriority.HIGH)
Analytics.trackClick("buy_button")
Analytics.screen("Checkout")

Analytics.identify("user_42", mapOf("plan" to "pro"))   // on login
Analytics.reset()                                       // on logout

Analytics.setSuperProperties(mapOf("ab_group" to "B"))  // merged into every event
Analytics.optOut(true)
Analytics.flush()                                       // upload now
```

`HIGH` priority events are evicted last when the queue is full. Upload order is always chronological.

## UI tracking

Impressions count once per screen visit, after an item has been at least 50% visible for 1 second (configurable with `ImpressionConfig`). Visibility pauses while a dialog has focus. Clicks are debounced and linked to the item's impression. Rotation keeps the same visit.

**Compose**

```kotlin
TrackScreen("product_list") {
    LazyColumn {
        items(products, key = { it.id }) { product ->
            val onClick = rememberTrackedClick("product_click", key = product.id) { open(product) }
            ProductRow(product, Modifier.trackImpression(key = product.id).clickable(onClick = onClick))
        }
    }
}
```

**Views / Fragments**

```kotlin
// Fragment.onViewCreated
val tracker = trackScreen("feed", viewLifecycleOwner, this, view)
recyclerView.trackImpressions(tracker, itemKey = { adapter.items[it].id })
button.setTrackedOnClickListener(tracker, "buy_click", key = item.id) { buy() }
```

## Configuration

| Option | Default | Notes |
|---|---|---|
| `batchSize` | 50 | Events per request; also the queue depth that triggers a flush (1–500) |
| `flushInterval` | 30s | Upload cadence while the process is alive |
| `maxQueueSize` | 10,000 | Per process; oldest `NORMAL` events evicted first |
| `eventTtl` | 7 days | Undelivered events older than this are dropped |
| `maxAttempts` | 25 | Server failures before an event is dropped; network errors don't count |
| `gzip` | true | Request body compression |
| `sessionTimeout` | 30 min | Background inactivity that ends a session |
| `newSessionOnColdStart` | false | Off because Android kills background processes often |
| `backgroundUploads` | true | WorkManager jobs for delivery after process death |
| `beforeSend` | null | `(AnalyticsEvent) -> AnalyticsEvent?`; return null to drop |
| `listener` | null | `AnalyticsListener`: queued, uploaded, failed, dropped (with `DropReason`) |
| `networkClient`, `serializer`, `retryPolicy`, `responseClassifier` | built-in | Pluggable to match your backend |

Invalid values fail fast in `AnalyticsConfig(...)`.

### Extra destinations

```kotlin
AnalyticsConfig("https://api.myapp.com/v1/events") {
    destination("warehouse", "https://collect.warehouse.com/events") {
        filter = { it.type == EventType.CLICK || it.type == EventType.SCREEN }
        batchSize = 100
    }
}
```

Each destination keeps its own rows, so one backend failing never blocks or duplicates delivery to another.

## Wire format

`POST` with `Content-Type: application/json` and, by default, `Content-Encoding: gzip`:

```json
{
  "batch_id": "6f1c…",
  "sent_at": 1728470000000,
  "events": [
    {
      "event_id": "…", "name": "purchase", "type": "custom",
      "properties": { "sku": "A1", "price": 9.99 },
      "user_id": "user_42", "anonymous_id": "…", "device_id": "…", "session_id": "…",
      "seq": 1042, "client_ts": 1728469999000, "elapsed_realtime": 123456,
      "app_version": "1.4.0", "sdk_version": "0.1.0", "os_version": "16", "schema_version": 1,
      "context": { "os": "android", "api_level": 36, "model": "…", "locale": "en-IN", "timezone": "Asia/Kolkata", "network": "wifi", "process": "main", "…": "…" }
    }
  ]
}
```

- `type` is one of `custom`, `click`, `impression`, `screen`, `identify`, `system`.
- Dedupe on `event_id`. `batch_id` is unique per request.
- Use `server_received_at - sent_at` to correct device clock skew.
- `seq` is unique per install and increasing; gaps are normal.
- The SDK emits `session_start` and `sdk_events_dropped` system events.

**How responses are handled**

| Response | What the SDK does |
|---|---|
| 2xx | Success; the events are removed from the queue |
| 401, 403, 408, 429, 5xx | Retried with backoff |
| 413 | The batch is split and resent |
| Other 4xx | The batch is bisected and only the rejected event is dropped |

### Remote config

A `GET` to `remoteConfigUrl` (ETag-revalidated, refreshed hourly by default):

```json
{
  "tracking_enabled": true,
  "uploads_enabled": true,
  "sample_rate": 1.0,
  "event_sample_rates": { "scroll": 0.1 },
  "blocked_events": ["debug_*", "legacy_click"],
  "batch_size": 50,
  "flush_interval_seconds": 30,
  "ttl_seconds": 3600
}
```

Sampling is per device, not per event, so a sampled-in device sends complete sessions.

## Sample app

```sh
./gradlew :app:installDebug
```

The debug panel switches the mock backend between healthy, offline, `503 + Retry-After` and `413`. It also switches remote config between normal, pause uploads, block impressions and 0% sampling. It has login/logout, a dialog that pauses impressions, a `:remote` process demo and a Views demo screen. Watch delivery with:

```sh
adb logcat -s MockBackend WarehouseBackend Analytics
```

## Tests

```sh
./gradlew testDebugUnitTest                         # JVM / Robolectric tests, all modules
./gradlew :analytics-core:connectedDebugAndroidTest # on-device end-to-end (emulator or device)
./gradlew lintDebug
```

The on-device suite (`analytics-core/src/androidTest`) runs the public API through the real SQLite queue and `HttpURLConnection` against a local HTTP server. It covers payloads, identity, sessions, retries, batch splitting, opt-out, queue eviction, concurrency, the upload worker and app backgrounding.
