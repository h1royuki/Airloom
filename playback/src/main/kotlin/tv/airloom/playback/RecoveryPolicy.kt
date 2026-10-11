package tv.airloom.playback

import tv.airloom.core.PlaybackNetworkSettings

/** Pure, monotonic-clock recovery budget shared by errors and the buffering watchdog. */
internal class RecoveryPolicy {
    var attempts = 0
        private set
    private var bufferingSince: Long? = null
    private var playingSince: Long? = null
    private var lastPosition: Long? = null

    fun reset() { attempts = 0; resetTimers() }
    fun resetTimers() { bufferingSince = null; playingSince = null; lastPosition = null }
    fun nextDelay(settings: PlaybackNetworkSettings): Long? {
        if (attempts >= settings.reconnectAttempts) return null
        val delay = (settings.retryDelayMs.toLong() * (1L shl attempts)).coerceAtMost(15_000)
        attempts++
        resetTimers()
        return delay
    }
    fun tick(now: Long, wantsPlay: Boolean, buffering: Boolean, playing: Boolean, position: Long, timeout: Int): Boolean {
        if (!wantsPlay) { resetTimers(); return false }
        if (playing && lastPosition != null && position > lastPosition!!) {
            if (playingSince == null) playingSince = now
            if (now - playingSince!! >= 60_000) attempts = 0
        } else playingSince = null
        lastPosition = position
        if (!buffering) { bufferingSince = null; return false }
        if (bufferingSince == null) bufferingSince = now
        return now - bufferingSince!! >= timeout
    }

    companion object {
        fun retryableHttp(status: Int, mediaSegment: Boolean): Boolean =
            status == 408 || status == 429 || status in 500..599 ||
                (mediaSegment && status in listOf(404, 410))
    }
}
