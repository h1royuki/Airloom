package tv.airloom.app

import org.junit.Assert.*
import org.junit.Test
import tv.airloom.core.*

class UiPoliciesTest {
    @Test fun controlsStayVisibleForPauseBufferingAndInteraction() {
        assertTrue(UiPolicies.autoHide(true, false, false, false, false))
        assertFalse(UiPolicies.autoHide(false, false, false, false, false))
        assertFalse(UiPolicies.autoHide(true, true, false, false, false))
        assertFalse(UiPolicies.autoHide(true, false, true, false, false))
        assertFalse(UiPolicies.autoHide(true, false, false, true, false))
        assertFalse(UiPolicies.autoHide(true, false, false, false, true))
    }
    @Test fun draftSeekStaysInsideAvailableRecording() {
        assertEquals(0, UiPolicies.draftSeek(5_000, -10_000, 30_000))
        assertEquals(30_000, UiPolicies.draftSeek(25_000, 10_000, 30_000))
        assertEquals(20_000, UiPolicies.draftSeek(10_000, 10_000, 30_000))
    }
    @Test fun nextProgrammeIsAnnouncedOnlyInTheLastFiveMinutes() {
        val end = 3_600_000L
        assertFalse(UiPolicies.announcesNext(end, end - 300_001))
        assertTrue(UiPolicies.announcesNext(end, end - 300_000))
        assertTrue(UiPolicies.announcesNext(end, end - 1))
    }
    @Test fun epgWindowCoversViewportAndMovesOncePerStep() {
        val rows = (0 until 100).map { Channel("c$it", "p", "Channel $it", "https://example.invalid/$it") }
        assertEquals(emptyList<String>(), UiPolicies.epgWindow(rows, -1))
        assertEquals(emptyList<String>(), UiPolicies.epgWindow(emptyList(), 0))
        assertEquals((0 until 40).map { "c$it" }, UiPolicies.epgWindow(rows, 0))
        // Every row of a step shares one window, which still holds a screenful below the first row.
        for (first in 20..29) {
            val window = UiPolicies.epgWindow(rows, first)
            assertEquals(UiPolicies.epgWindow(rows, 20), window)
            assertTrue((first..first + 15).all { "c$it" in window })
        }
        assertNotEquals(UiPolicies.epgWindow(rows, 29), UiPolicies.epgWindow(rows, 30))
        assertEquals((80 until 100).map { "c$it" }, UiPolicies.epgWindow(rows, 95))
        assertEquals(listOf("c2"), UiPolicies.epgWindow(rows.take(3), 40))
    }
    @Test fun lastChannelDoesNotSubstituteAnotherOrHiddenChannel() {
        val channel = Channel("last", "p", "Last", "https://example.invalid/live", group = "Hidden")
        assertEquals(channel, UiPolicies.lastChannel(listOf(channel), emptyList(), "last"))
        assertNull(UiPolicies.lastChannel(listOf(channel), emptyList(), "removed"))
        assertNull(UiPolicies.lastChannel(listOf(channel.copy(hidden = true)), emptyList(), "last"))
        assertNull(UiPolicies.lastChannel(listOf(channel), listOf(Category("p", "Hidden", 1, hidden = true)), "last"))
    }
    @Test fun settingsPatchPreservesIndependentNewerPersistedFields() {
        val old = AppSettings()
        val proposed = old.copy(textSize = 2)
        val latest = old.copy(accent = Accent.TEAL, recentChannelIds = listOf("new"))
        val saved = proposed.patch(old)(latest)
        assertEquals(2, saved.textSize)
        assertEquals(Accent.TEAL, saved.accent)
        assertEquals(listOf("new"), saved.recentChannelIds)
    }
    @Test fun providerCategoryNamesDoNotSelectSystemSections() {
        for (name in listOf("Все каналы", "Избранное", "Последние")) {
            val selection = ChannelSelection.Category("p", name)
            assertEquals(ChannelListKind.CATEGORY, ChannelLists.scope("p", selection)?.kind)
            assertEquals(name, ChannelLists.scope("p", selection)?.category)
        }
    }
}
