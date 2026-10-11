package tv.airloom.app.ui

import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.delay
import tv.airloom.core.Channel
import tv.airloom.playback.FramePreviewService
import tv.airloom.playback.PreviewFrames

internal val LocalPreviewService = staticCompositionLocalOf<FramePreviewService?> { null }
private const val PREVIEW_SETTLE_MS = 700L

@Composable
internal fun ChannelFrame(
    channel: Channel?, enabled: Boolean, modifier: Modifier = Modifier,
    playlistLocation: String? = null, fullscreen: Boolean = false, captureEnabled: Boolean = enabled,
) {
    val context = LocalContext.current
    val service = LocalPreviewService.current ?: remember(context) { PreviewFrames.shared(context) }
    val key = remember(channel?.id, channel?.url, channel?.userAgent, channel?.referer) { channel?.let(service::key) }
    var image by remember(key) { mutableStateOf(key?.let(service::cached)) }
    var texture by remember { mutableStateOf<TextureView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var refreshPending by remember(key, enabled, captureEnabled, foreground) {
        mutableStateOf(key != null && service.needsRefresh(key) && !service.coolingDown(key))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(key, enabled, captureEnabled, texture, foreground) {
        val selected = channel ?: return@LaunchedEffect
        val surface = texture ?: return@LaunchedEffect
        if (!foreground || !enabled || !captureEnabled || !refreshPending || key?.let(service::coolingDown) == true) return@LaunchedEffect
        // Passing focus must not build a decoder: capture only once the user has settled on a channel.
        delay(PREVIEW_SETTLE_MS)
        val fresh = service.capture(selected, surface)
        if (fresh != null) image = fresh
        refreshPending = false
    }
    val alpha by animateFloatAsState(if (image != null && enabled) 1f else 0f, tween(motionDuration(180)), label = "preview")
    Box(modifier.then(if (fullscreen) Modifier else Modifier.clip(PanelShape))
        .background(LocalPalette.current.container).testTag("channel_frame"), contentAlignment = Alignment.Center) {
        if (enabled && refreshPending && channel != null && captureEnabled && foreground) AndroidView(
            factory = { TextureView(it).also { view -> texture = view } }, modifier = Modifier.fillMaxSize(),
            onRelease = { texture = null },
        )
        Box(Modifier.fillMaxSize().background(LocalPalette.current.container), contentAlignment = Alignment.Center) {
            if (fullscreen) AirloomMark(Modifier.size(100.dp))
            else ChannelLogo(channel, true, 52, Modifier.size(96.dp), playlistLocation)
        }
        image?.takeIf { enabled }?.let { bitmap ->
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }
                .testTag("channel_frame_image"), contentScale = ContentScale.Fit)
        }
    }
}
