package tv.airloom.app.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal val LocalMotionEnabled = staticCompositionLocalOf { true }

@Composable
internal fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun enabled() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var active by remember(context, lifecycle) { mutableStateOf(enabled()) }
    DisposableEffect(context, lifecycle) {
        val listener = LifecycleEventObserver { _, _ -> active = enabled() }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { active = enabled() }
        }
        lifecycle.addObserver(listener)
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { lifecycle.removeObserver(listener); context.contentResolver.unregisterContentObserver(observer) }
    }
    return active
}

@Composable
internal fun motionDuration(ms: Int): Int = if (LocalMotionEnabled.current) ms else 0

@Composable
internal fun pulse(durationMs: Int, low: Float = .55f): Float {
    if (!LocalMotionEnabled.current) return 1f
    val transition = rememberInfiniteTransition(label = "loading")
    return transition.animateFloat(low, 1f,
        infiniteRepeatable(tween(durationMs / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse").value
}
