package tv.airloom.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackNetworkSettingsTest {
    @Test fun invalidStoredSettingsReturnDefaults() {
        assertEquals(PlaybackNetworkSettings(), PlaybackNetworkSettings(-1, -1, -1, -1, -1, -1, -1, -1).normalized())
    }
    @Test fun profilesOnlyChangeBuffersAndCustomValuesAreRecognized() {
        val custom = PlaybackNetworkSettings(reconnectAttempts = 9, readTimeoutMs = 60_000)
        val fast = custom.withProfile(0)
        assertEquals(9, fast.reconnectAttempts)
        assertEquals(60_000, fast.readTimeoutMs)
        assertEquals(0, fast.profile)
        assertEquals(-1, fast.copy(bufferMs = 30_000).profile)
        assertEquals(1, PlaybackNetworkSettings().profile)
    }
    @Test fun stalePatchPreservesOtherNetworkAndAppearanceChanges() {
        val before = AppSettings()
        val changed = before.copy(playbackNetwork = before.playbackNetwork.copy(reconnectAttempts = 2))
        val current = before.copy(accent = Accent.PEACH, playbackNetwork = before.playbackNetwork.copy(bufferMs = 90_000))
        val result = changed.patch(before)(current)
        assertEquals(2, result.playbackNetwork.reconnectAttempts)
        assertEquals(90_000, result.playbackNetwork.bufferMs)
        assertEquals(Accent.PEACH, result.accent)
    }
    @Test fun allBufferOptionsHaveValidOrdering() {
        for (buffer in PlaybackNetworkSettings.buffers)
            for (start in PlaybackNetworkSettings.startBuffers)
                for (resume in PlaybackNetworkSettings.resumeBuffers) {
                    assertTrue(buffer >= start)
                    assertTrue(buffer >= resume)
                }
    }
}
