@file:JvmName("ScreenTracking")

package com.example.analytics.views

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.analytics.tracking.ImpressionConfig
import com.example.analytics.tracking.PauseReason
import com.example.analytics.tracking.ScreenVisitTracker

/**
 * Binds a screen visit to a View-based screen. Same rules as the Compose `TrackScreen`:
 * a visit runs ON_START..ON_STOP (configuration changes don't end it), impressions pause at
 * ON_PAUSE and while the window lacks focus (dialogs, notification shade).
 *
 * ```
 * // Activity.onCreate, after setContentView:
 * val tracker = trackScreen("feed", this, this, window.decorView)
 * // Fragment.onViewCreated:
 * val tracker = trackScreen("feed", viewLifecycleOwner, this, view)
 * ```
 * The tracker is retained in [viewModelStoreOwner], so it survives rotation; [properties] are
 * read at each visit start.
 */
@JvmOverloads
fun trackScreen(
    name: String,
    lifecycleOwner: LifecycleOwner,
    viewModelStoreOwner: ViewModelStoreOwner,
    rootView: View,
    properties: () -> Map<String, Any?> = { emptyMap() },
    config: ImpressionConfig = ImpressionConfig(),
    pauseWhenWindowUnfocused: Boolean = true,
): ScreenVisitTracker {
    val factory = viewModelFactory { initializer { TrackerHolder(ScreenVisitTracker(name, config)) } }
    val tracker = ViewModelProvider(viewModelStoreOwner, factory)["com.example.analytics.screen:$name", TrackerHolder::class.java].tracker
    // Not just rootView.context: a DecorView's context wraps the Application on API 24+, which would
    // make every rotation look like leaving the screen.
    val activity = lifecycleOwner as? Activity
        ?: viewModelStoreOwner as? Activity
        ?: rootView.context.findActivity()
    fun changingConfigurations() = activity?.isChangingConfigurations == true

    val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        tracker.setPaused(PauseReason.WINDOW_FOCUS, pauseWhenWindowUnfocused && !focused)
    }
    tracker.setPaused(PauseReason.LIFECYCLE, true)
    tracker.setPaused(PauseReason.WINDOW_FOCUS, pauseWhenWindowUnfocused && !rootView.hasWindowFocus())
    rootView.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)

    lifecycleOwner.lifecycle.addObserver(object : LifecycleEventObserver {
        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            when (event) {
                Lifecycle.Event.ON_START -> tracker.startVisit(properties())
                Lifecycle.Event.ON_RESUME -> tracker.setPaused(PauseReason.LIFECYCLE, false)
                Lifecycle.Event.ON_PAUSE -> tracker.setPaused(PauseReason.LIFECYCLE, true)
                Lifecycle.Event.ON_STOP -> if (!changingConfigurations()) tracker.endVisit()
                Lifecycle.Event.ON_DESTROY -> {
                    source.lifecycle.removeObserver(this)
                    if (rootView.viewTreeObserver.isAlive) rootView.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
                }
                else -> Unit
            }
        }
    })
    return tracker
}

private class TrackerHolder(val tracker: ScreenVisitTracker) : ViewModel() {
    override fun onCleared() = tracker.endVisit()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
