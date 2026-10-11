package tv.airloom.app

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import tv.airloom.app.ui.AirloomApp
import tv.airloom.app.ui.AirloomPlayerOverlay
import tv.airloom.app.ui.LocalProviderProfile
import tv.airloom.app.ui.LocalPreviewService

@AndroidEntryPoint
@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity: ComponentActivity() {
    private val model: AirloomViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        lifecycleScope.launch { model.exits.collect { finish() } }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            var bounds by remember { mutableStateOf<Rect?>(null) }
            val density = LocalDensity.current
            LaunchedEffect(state.screen) { if (state.screen != Screen.PLAYER) bounds = null }
            LaunchedEffect(state.player.isPlaying, state.player.isBuffering, state.player.recovery, state.player.playWhenReady) {
                if (state.player.isPlaying || state.player.isBuffering || (state.player.recovery != null && state.player.playWhenReady)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            Box(Modifier.fillMaxSize().background(Color(0xff17171e))) {
                CompositionLocalProvider(LocalPreviewService provides model.previews) { AirloomApp(state, model) { bounds = it } }
                val rectangle = bounds
                val hasVideo = rectangle != null && state.player.channel != null && state.screen == Screen.PLAYER
                val width = with(density) { (rectangle?.width ?: 1f).toDp() }
                val height = with(density) { (rectangle?.height ?: 1f).toDp() }
                AndroidView(
                    factory = { context -> PlayerView(context).apply {
                        useController = false
                        isFocusable = false
                        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        setKeepContentOnPlayerReset(false)
                        model.playback.attachView(this)
                    } },
                    modifier = Modifier.offset { IntOffset((rectangle?.left ?: 0f).roundToInt(), (rectangle?.top ?: 0f).roundToInt()) }
                        .size(if (hasVideo) width else 1.dp, if (hasVideo) height else 1.dp)
                        .clip(RoundedCornerShape(if (state.screen == Screen.PLAYER) 0.dp else 20.dp)),
                    update = { view -> view.visibility = if (hasVideo) View.VISIBLE else View.INVISIBLE },
                    onRelease = { model.playback.detachView(it) },
                )
                // Playback positions are collected only by the visible player timeline, not by the whole overlay.
                CompositionLocalProvider(LocalProviderProfile provides state.provider, LocalPreviewService provides model.previews) {
                    AirloomPlayerOverlay(state, model, model.playback.state)
                }
            }
        }
    }

    override fun onStop() { model.background(); super.onStop() }
}
