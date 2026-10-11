package tv.airloom.playback

import org.junit.Assert.*
import org.junit.Test
import tv.airloom.core.PlaybackNetworkSettings

class RecoveryPolicyTest {
    @Test fun exactBudgetWithCappedBackoffAndDisabledMode() {
        val policy = RecoveryPolicy()
        val settings = PlaybackNetworkSettings()
        assertEquals(listOf(2000L, 4000L, 8000L, 15000L, 15000L), (1..5).map { policy.nextDelay(settings) })
        assertNull(policy.nextDelay(settings))
        policy.reset()
        assertNull(policy.nextDelay(settings.copy(reconnectAttempts = 0)))
        assertEquals(0, policy.attempts)
    }
    @Test fun briefPlaybackCannotResetBudgetButMinuteOfProgressDoes() {
        val policy = RecoveryPolicy()
        policy.nextDelay(PlaybackNetworkSettings())
        for (i in 0..50) policy.tick(i * 1000L, true, false, true, i * 1000L, 60000)
        assertEquals(1, policy.attempts)
        policy.tick(51_000, true, true, false, 50_000, 60000)
        for (i in 52..112) policy.tick(i * 1000L, true, false, true, i * 1000L, 60000)
        assertEquals(0, policy.attempts)
    }
    @Test fun pausedOrMotionlessPlaybackNeverResetsBudget() {
        val policy = RecoveryPolicy()
        policy.nextDelay(PlaybackNetworkSettings())
        for (i in 0..120) policy.tick(i * 1000L, true, false, true, 0, 60000)
        assertEquals(1, policy.attempts)
        for (i in 121..240) policy.tick(i * 1000L, false, false, false, i * 1000L, 60000)
        assertEquals(1, policy.attempts)
    }
    @Test fun bufferingWatchdogPausesAndSeekResetsItsClock() {
        val policy = RecoveryPolicy()
        assertFalse(policy.tick(0, true, true, false, 0, 30000))
        assertFalse(policy.tick(29000, true, true, false, 0, 30000))
        assertFalse(policy.tick(30000, false, true, false, 0, 30000))
        assertFalse(policy.tick(100000, true, true, false, 0, 30000))
        policy.resetTimers()
        assertFalse(policy.tick(130000, true, true, false, 0, 30000))
        assertTrue(policy.tick(160000, true, true, false, 0, 30000))
    }
    @Test fun certificateDecoderAndMissingSourceFailuresDoNotRestart() {
        fun retry(code: Int, ssl: Boolean = false, io: Boolean = false) =
            PlaybackFailurePolicy.retryable(code, null, ssl, io, false)
        assertTrue(retry(androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
        assertTrue(retry(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertTrue(retry(androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED, io = true))
        assertFalse(retry(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, ssl = true, io = true))
        assertFalse(retry(androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, io = true))
        assertFalse(retry(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED, io = true))
        assertFalse(retry(androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, io = true))
    }

    @Test fun onlyTransientHttpAndMissingSegmentsRetry() {
        for (status in listOf(408, 429, 500, 503, 599)) assertTrue(RecoveryPolicy.retryableHttp(status, false))
        for (status in listOf(400, 401, 403, 404, 410, 422)) assertFalse(RecoveryPolicy.retryableHttp(status, false))
        assertTrue(RecoveryPolicy.retryableHttp(404, true))
        assertTrue(RecoveryPolicy.retryableHttp(410, true))
        assertFalse(RecoveryPolicy.retryableHttp(403, true))
    }
}
