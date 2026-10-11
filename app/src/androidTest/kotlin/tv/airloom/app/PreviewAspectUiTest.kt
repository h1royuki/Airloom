package tv.airloom.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import tv.airloom.app.ui.*
import tv.airloom.core.Channel
import tv.airloom.playback.FramePreviewService

class PreviewAspectUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun wideContainerKeepsTopAndBottomOfCachedFrameWithoutCropping() {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.GREEN)
        val paint = Paint().apply { color = Color.RED }
        canvas.drawRect(0f, 0f, 640f, 72f, paint)
        paint.color = Color.BLUE
        canvas.drawRect(0f, 288f, 640f, 360f, paint)
        val service = object : FramePreviewService {
            override fun key(channel: Channel) = "synthetic-aspect"
            override fun cached(key: String) = bitmap
            override fun coolingDown(key: String) = false
            override suspend fun capture(channel: Channel, texture: TextureView): Bitmap? = error("Cached preview must not open a stream")
            override fun stop() = Unit
        }
        compose.setContent {
            AirloomTheme {
                CompositionLocalProvider(LocalPreviewService provides service) {
                    ChannelFrame(Channel("test", "fixture", "Test", "https://example.invalid/never-request"), true, Modifier.size(320.dp, 90.dp))
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)
        val pixels = compose.onNodeWithTag("channel_frame").captureToImage().toPixelMap()
        val top = pixels[pixels.width / 2, pixels.height / 10]
        val bottom = pixels[pixels.width / 2, pixels.height * 9 / 10]
        assertTrue("Top red stripe must remain visible", top.red > .9f && top.green < .1f)
        assertTrue("Bottom blue stripe must remain visible", bottom.blue > .9f && bottom.green < .1f)
    }
}
