package tv.airloom.app

import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import tv.airloom.core.Channel
import tv.airloom.playback.FirstFrameCapture
import tv.airloom.playback.PreviewFrames
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
class FirstFrameUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun realDecoderCapturesSilentFrameAndStopReleasesIt() {
        val clip = File(compose.activity.cacheDir, "synthetic-preview.mp4")
        syntheticVideo(clip)
        var texture: TextureView? = null
        compose.setContent { AndroidView(factory = { TextureView(it).also { texture = it } }, modifier = Modifier.fillMaxSize()) }
        compose.waitUntil(5_000) { texture?.isAvailable == true }
        var now = 1_000L
        val previews = PreviewFrames(compose.activity, tv.airloom.core.AirloomRuntime(now = { now }))
        val channel = Channel("synthetic", "p", "Synthetic", clip.toURI().toString())
        try {
            runBlocking {
                val capture = async { previews.capture(channel, texture!!) }
                val active = withTimeout(5_000) { FirstFrameCapture.state.first { it.active } }
                assertTrue(active.muted)
                val bitmap = withTimeout(8_000) { capture.await() }
                assertNotNull(bitmap)
                assertEquals(640, bitmap!!.width)
                assertEquals(360, bitmap.height)
                assertFalse(FirstFrameCapture.state.value.active)
                assertSame(bitmap, previews.cached(previews.key(channel)))
                val key = previews.key(channel)
                assertFalse(previews.needsRefresh(key))
                now += 600_001
                assertSame(bitmap, previews.cached(key))
                assertTrue(previews.needsRefresh(key))
                val refreshed = previews.capture(channel, texture!!)
                assertNotNull(refreshed)
                assertNotSame(bitmap, refreshed)
                assertFalse(previews.needsRefresh(key))
                clip.delete()
                now += 600_001
                assertNull(previews.capture(channel, texture!!))
                assertSame(refreshed, previews.cached(key))
                assertTrue(previews.coolingDown(key))
                assertSame(refreshed, previews.capture(channel, texture!!))
                now += 15_001
                assertFalse(previews.coolingDown(key))
                syntheticVideo(clip)
                val other = async { previews.capture(channel.copy(id = "other"), texture!!) }
                withTimeout(5_000) { FirstFrameCapture.state.first { it.active } }
                compose.runOnUiThread { previews.stop() }
                other.join()
                assertTrue(other.isCancelled)
                assertFalse(FirstFrameCapture.state.value.active)
            }
        } finally { compose.runOnUiThread { previews.stop() }; clip.delete() }
    }
}
