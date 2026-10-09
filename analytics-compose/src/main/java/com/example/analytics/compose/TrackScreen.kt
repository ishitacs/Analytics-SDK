package com.example.analytics.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.analytics.tracking.ImpressionConfig
import com.example.analytics.tracking.PauseReason
import com.example.analytics.tracking.ScreenVisitTracker

/** The tracker of the enclosing [TrackScreen], or null outside one. */
val LocalScreenVisitTracker = staticCompositionLocalOf<ScreenVisitTracker?> { null }

/**
 * Marks [content] as one analytics screen.
 *
 * - Tracks a `screen` event each time a visit starts.
 * - A visit starts at ON_START and ends at ON_STOP of the nearest lifecycle owner (the activity, or
 *   the navigation back-stack entry with Navigation Compose), so leaving and returning — including
 *   backgrounding the app — starts a new visit. Rotation and other configuration changes do not.
 * - Impressions inside ([Modifier.trackImpression][trackImpression]) pause at ON_PAUSE and, unless
 *   [pauseWhenWindowUnfocused] is false, while a dialog or the notification shade has focus.
 *
 * [config] is read when the screen is first composed; [properties] are read at each visit start.
 */
@Composable
fun TrackScreen(
    name: String,
    properties: Map<String, Any?> = emptyMap(),
    config: ImpressionConfig = ImpressionConfig(),
    pauseWhenWindowUnfocused: Boolean = true,
    content: @Composable () -> Unit,
) {
    // A ViewModel survives configuration changes, so rotation keeps the same visit.
    val holder = viewModel(key = "com.example.analytics.screen:$name") { TrackerHolder(ScreenVisitTracker(name, config)) }
    TrackScreen(holder.tracker, properties, pauseWhenWindowUnfocused, content)
}

/** Variant taking an explicit tracker (custom sink/scheduler, tests). */
@Composable
fun TrackScreen(
    tracker: ScreenVisitTracker,
    properties: Map<String, Any?> = emptyMap(),
    pauseWhenWindowUnfocused: Boolean = true,
    content: @Composable () -> Unit,
) {
    val currentProperties by rememberUpdatedState(properties)
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalContext.current.findActivity()

    DisposableEffect(lifecycleOwner, tracker) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> tracker.startVisit(currentProperties)
                Lifecycle.Event.ON_RESUME -> tracker.setPaused(PauseReason.LIFECYCLE, false)
                Lifecycle.Event.ON_PAUSE -> tracker.setPaused(PauseReason.LIFECYCLE, true)
                Lifecycle.Event.ON_STOP ->
                    if (activity?.isChangingConfigurations != true) tracker.endVisit()
                else -> Unit
            }
        }
        // Paused until ON_RESUME; addObserver replays the events up to the current state.
        tracker.setPaused(PauseReason.LIFECYCLE, true)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (activity?.isChangingConfigurations != true) tracker.endVisit()
        }
    }

    val windowFocused = LocalWindowInfo.current.isWindowFocused
    DisposableEffect(tracker, windowFocused, pauseWhenWindowUnfocused) {
        tracker.setPaused(PauseReason.WINDOW_FOCUS, pauseWhenWindowUnfocused && !windowFocused)
        onDispose { }
    }

    CompositionLocalProvider(LocalScreenVisitTracker provides tracker, content = content)
}

private class TrackerHolder(val tracker: ScreenVisitTracker) : ViewModel() {
    override fun onCleared() = tracker.endVisit()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
