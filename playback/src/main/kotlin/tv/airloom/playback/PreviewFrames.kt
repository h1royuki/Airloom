package tv.airloom.playback

import android.content.Context
import android.graphics.Bitmap
import android.view.TextureView
import kotlinx.coroutines.withTimeoutOrNull
import tv.airloom.core.AirloomRuntime
import tv.airloom.core.Channel

interface FramePreviewService {
    fun key(channel: Channel): String
    fun cached(key: String): Bitmap?
    fun needsRefresh(key: String): Boolean = cached(key) == null
    fun coolingDown(key: String): Boolean
    suspend fun capture(channel: Channel, texture: TextureView): Bitmap?
    fun stop()
}

/** At most twenty-four 640×360 frames in RAM. No stream images or credentials are written to disk. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PreviewFrames(context: Context, private val runtime: AirloomRuntime = AirloomRuntime()) : FramePreviewService {
    private val capture = FirstFrameCapture(context)
    private data class Entry(val bitmap: Bitmap?, val freshUntil: Long, val retryAfter: Long = 0)
    private val entries = object : LinkedHashMap<String, Entry>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 24
    }

    // The key only indexes this in-memory cache, so it needs no digest: ChannelFrame computes it during composition.
    override fun key(channel: Channel): String = listOf(channel.id, channel.url, channel.userAgent, channel.referer).joinToString("\u0000")
    @Synchronized override fun cached(key: String): Bitmap? = entries[key]?.bitmap
    @Synchronized override fun needsRefresh(key: String): Boolean = entries[key]?.let { it.bitmap == null || it.freshUntil <= runtime.now() } ?: true
    @Synchronized override fun coolingDown(key: String): Boolean = entries[key]?.let { it.retryAfter > runtime.now() } ?: false
    override suspend fun capture(channel: Channel, texture: TextureView): Bitmap? {
        val key = key(channel)
        if (!needsRefresh(key) || coolingDown(key)) return cached(key)
        val bitmap = withTimeoutOrNull(7_000) { capture.capture(channel, texture) }
        synchronized(this) {
            entries[key] = if (bitmap != null) Entry(bitmap, runtime.now() + 600_000)
            else Entry(entries[key]?.bitmap, entries[key]?.freshUntil ?: 0, runtime.now() + 15_000)
        }
        return bitmap
    }
    override fun stop() = FirstFrameCapture.stopActive()

    companion object {
        @Volatile private var instance: PreviewFrames? = null
        fun shared(context: Context): PreviewFrames = instance ?: synchronized(this) {
            instance ?: PreviewFrames(context.applicationContext).also { instance = it }
        }
    }
}
