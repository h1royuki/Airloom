package tv.airloom.app.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp

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

/** Shared motion roles, in milliseconds. Focus (120) and the layers (200) keep their own values where they are declared. */
internal object Motion {
    /** Content replaced in place: a title, a logo, a schedule. */
    const val Swap = 150
    /** Content arriving where a skeleton was. */
    const val Enter = 180
    /** Delay between neighbouring rows of a cascade, and how many rows take part in it. */
    const val Stagger = 25
    const val StaggerRows = 8
    /** A skeleton or a stale layer leaving. */
    const val Leave = 120
    const val Screen = 200
    val Decelerate = CubicBezierEasing(.05f, .7f, .1f, 1f)
}

/**
 * One clock for a group of skeleton blocks, read only while drawing: each row is a step behind the one
 * above, so the group reads as a wave moving down instead of a block that blinks. Null when motion is off.
 */
@Composable
internal fun skeletonClock(): State<Float>? {
    if (!LocalMotionEnabled.current) return null
    return rememberInfiniteTransition(label = "skeleton").animateFloat(0f, 1f,
        infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "wave")
}

internal fun Modifier.skeletonWave(clock: State<Float>?, index: Int): Modifier = if (clock == null) this else graphicsLayer {
    alpha = .7f + .3f * kotlin.math.cos((clock.value - index * .065f) * 2f * Math.PI.toFloat())
}

/**
 * Keeps [content] composed while it fades out, so a skeleton dissolves into what replaces it instead of
 * being cut. The alpha is read only while drawing; the composition changes once, when the fade has ended.
 */
@Composable
internal fun FadingPresence(visible: Boolean, modifier: Modifier = Modifier, durationMs: Int = Motion.Leave, content: @Composable () -> Unit) {
    val alpha = animateFloatAsState(if (visible) 1f else 0f, tween(motionDuration(durationMs)), label = "presence")
    val present by remember { derivedStateOf { alpha.value > 0f } }
    if (visible || present) Box(modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
}

/**
 * Entrance of one row of a cascade. [progress] runs 0..1 over the whole cascade and is read only while
 * drawing; [slot] is the row's place in it. Rows past the last slot arrive with the last one.
 */
internal fun Modifier.cascade(progress: State<Float>, slot: () -> Int): Modifier = graphicsLayer {
    val value = progress.value
    if (value >= 1f) return@graphicsLayer
    val total = Motion.Enter + Motion.Stagger * (Motion.StaggerRows - 1)
    val local = ((value * total - slot().coerceIn(0, Motion.StaggerRows - 1) * Motion.Stagger) / Motion.Enter).coerceIn(0f, 1f)
    val eased = Motion.Decelerate.transform(local)
    alpha = eased
    translationY = (1f - eased) * 8.dp.toPx()
}
