package tv.airloom.playback

import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.Bitmap
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel as UpdateChannel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import tv.airloom.core.CatchupResolver
import tv.airloom.core.CatchupResult
import tv.airloom.core.Channel
import tv.airloom.core.PlaybackRequest
import java.util.concurrent.TimeUnit

data class FrameCaptureState(val active: Boolean = false, val muted: Boolean = true)

/** Captures one silent frame and releases its decoder, including on cancellation. */
@UnstableApi
class FirstFrameCapture(context: Context) {
    private data class Active(
        val player: ExoPlayer,
        val texture: TextureView,
        val listener: Player.Listener,
        val firstFrame: CompletableDeferred<Boolean>,
        val updates: UpdateChannel<Unit>,
        var surfaceListener: TextureView.SurfaceTextureListener? = null,
        var delegate: TextureView.SurfaceTextureListener? = null,
    )

    companion object {
        private var active: Active? = null
        private val captureState = MutableStateFlow(FrameCaptureState())
        val state = captureState.asStateFlow()
        private val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()

        /** Called on the main thread before foreground playback needs the decoder. */
        fun stopActive() {
            val capture = active ?: return
            active = null
            captureState.value = FrameCaptureState()
            capture.firstFrame.cancel()
            capture.updates.cancel()
            if (capture.texture.surfaceTextureListener === capture.surfaceListener) {
                capture.texture.surfaceTextureListener = capture.delegate
            }
            capture.player.removeListener(capture.listener)
            capture.player.clearVideoTextureView(capture.texture)
            capture.player.release()
        }
    }

    private val appContext = context.applicationContext
    suspend fun capture(channel: Channel, texture: TextureView): Bitmap? = withContext(Dispatchers.Main.immediate) {
        val resolved = CatchupResolver.resolve(PlaybackRequest.Live(channel)) as? CatchupResult.Available
            ?: return@withContext null
        // Media3 errors may contain private stream URLs. Keep its internal logging disabled.
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        val factory = DefaultMediaSourceFactory(
            DefaultDataSource.Factory(appContext, OkHttpDataSource.Factory(client)
                .setDefaultRequestProperties(resolved.stream.headers))
        )
        val player = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(factory)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 2_000, 100, 100).build())
            .build()
        val firstFrame = CompletableDeferred<Boolean>()
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame.complete(true) }
            override fun onPlayerError(error: PlaybackException) { firstFrame.complete(false) }
        }
        stopActive()
        val updates = UpdateChannel<Unit>(UpdateChannel.CONFLATED)
        val capture = Active(player, texture, listener, firstFrame, updates)
        active = capture
        try {
            player.volume = 0f
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setForceLowestBitrate(true).build()
            captureState.value = FrameCaptureState(active = true, muted = player.volume == 0f && C.TRACK_TYPE_AUDIO in player.trackSelectionParameters.disabledTrackTypes)
            player.addListener(listener)
            player.setVideoTextureView(texture)
            // ExoPlayer owns a listener too: forward every lifecycle event and restore it before release.
            val delegate = texture.surfaceTextureListener
            capture.delegate = delegate
            val surfaceListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    delegate?.onSurfaceTextureAvailable(surface, width, height)
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    delegate?.onSurfaceTextureSizeChanged(surface, width, height)
                }
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    updates.close()
                    return delegate?.onSurfaceTextureDestroyed(surface) ?: true
                }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    delegate?.onSurfaceTextureUpdated(surface)
                    updates.trySend(Unit)
                }
            }
            capture.surfaceListener = surfaceListener
            texture.surfaceTextureListener = surfaceListener
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(resolved.stream.url))
            player.prepare()
            player.play()
            withTimeoutOrNull(6_000) {
                if (!firstFrame.await()) return@withTimeoutOrNull null
                // A rendered frame may precede TextureView's update; wait for pixels, not a fixed delay.
                var bitmap: Bitmap? = null
                while (bitmap == null) {
                    updates.receive()
                    if (texture.isAvailable) bitmap = texture.getBitmap(640, 360)
                }
                bitmap
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            if (active === capture) stopActive()
        }
    }
}
