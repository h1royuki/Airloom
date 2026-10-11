package tv.airloom.core

/** Settings for foreground playback only. Durations are milliseconds. */
data class PlaybackNetworkSettings(
    val reconnectAttempts: Int = 5,
    val retryDelayMs: Int = 2_000,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 25_000,
    val bufferingTimeoutMs: Int = 60_000,
    val bufferMs: Int = 50_000,
    val startBufferMs: Int = 2_500,
    val resumeBufferMs: Int = 5_000,
) {
    fun normalized() = copy(
        reconnectAttempts = reconnectAttempts.takeIf { it in 0..10 } ?: 5,
        retryDelayMs = retryDelayMs.takeIf { it in retryDelays } ?: 2_000,
        connectTimeoutMs = connectTimeoutMs.takeIf { it in connectTimeouts } ?: 15_000,
        readTimeoutMs = readTimeoutMs.takeIf { it in readTimeouts } ?: 25_000,
        bufferingTimeoutMs = bufferingTimeoutMs.takeIf { it in bufferingTimeouts } ?: 60_000,
        bufferMs = bufferMs.takeIf { it in buffers } ?: 50_000,
        startBufferMs = startBufferMs.takeIf { it in startBuffers } ?: 2_500,
        resumeBufferMs = resumeBufferMs.takeIf { it in resumeBuffers } ?: 5_000,
    )
    val profile: Int get() = profiles.indexOfFirst { it == listOf(bufferMs, startBufferMs, resumeBufferMs) }
    fun withProfile(index: Int): PlaybackNetworkSettings {
        val values = profiles[index]
        return copy(bufferMs = values[0], startBufferMs = values[1], resumeBufferMs = values[2])
    }
    fun patch(previous: PlaybackNetworkSettings, current: PlaybackNetworkSettings) = current.copy(
        reconnectAttempts = if (reconnectAttempts != previous.reconnectAttempts) reconnectAttempts else current.reconnectAttempts,
        retryDelayMs = if (retryDelayMs != previous.retryDelayMs) retryDelayMs else current.retryDelayMs,
        connectTimeoutMs = if (connectTimeoutMs != previous.connectTimeoutMs) connectTimeoutMs else current.connectTimeoutMs,
        readTimeoutMs = if (readTimeoutMs != previous.readTimeoutMs) readTimeoutMs else current.readTimeoutMs,
        bufferingTimeoutMs = if (bufferingTimeoutMs != previous.bufferingTimeoutMs) bufferingTimeoutMs else current.bufferingTimeoutMs,
        bufferMs = if (bufferMs != previous.bufferMs) bufferMs else current.bufferMs,
        startBufferMs = if (startBufferMs != previous.startBufferMs) startBufferMs else current.startBufferMs,
        resumeBufferMs = if (resumeBufferMs != previous.resumeBufferMs) resumeBufferMs else current.resumeBufferMs,
    )
    companion object {
        val retryDelays = listOf(1000, 2000, 3000, 5000)
        val connectTimeouts = listOf(5000, 10000, 15000, 30000)
        val readTimeouts = listOf(10000, 15000, 25000, 30000, 60000)
        val bufferingTimeouts = listOf(30000, 60000, 90000, 120000)
        val buffers = listOf(15000, 30000, 50000, 90000)
        val startBuffers = listOf(1000, 2500, 5000)
        val resumeBuffers = listOf(3000, 5000, 10000)
        val profiles = listOf(listOf(15000, 1000, 3000), listOf(50000, 2500, 5000), listOf(90000, 5000, 10000))
    }
}
