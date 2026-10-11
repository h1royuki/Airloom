package tv.airloom.playback

import androidx.media3.common.PlaybackException

/** Classifies only safe scalar facts extracted from the exception chain. */
internal object PlaybackFailurePolicy {
    fun retryable(code: Int, httpStatus: Int?, sslFailure: Boolean, ioFailure: Boolean, mediaSegment: Boolean): Boolean {
        if (sslFailure) return false
        if (httpStatus != null) return RecoveryPolicy.retryableHttp(httpStatus, mediaSegment)
        return when (code) {
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> true
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> ioFailure
            else -> false
        }
    }
}
