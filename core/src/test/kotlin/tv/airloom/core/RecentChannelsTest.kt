package tv.airloom.core

import org.junit.Assert.*
import org.junit.Test

class RecentChannelsTest {
    @Test fun openingMovesToFrontWithoutDuplicates() {
        assertEquals(listOf("b", "a", "c"), RecentChannels.opened(listOf("a", "b", "c"), "b"))
        assertEquals(listOf("a", "b"), RecentChannels.opened(listOf("a", "a", "b"), ""))
    }
    @Test fun historyRetainsOnlyFiftyMostRecentChannels() {
        var ids = emptyList<String>()
        repeat(60) { ids = RecentChannels.opened(ids, "channel-$it") }
        assertEquals((59 downTo 10).map { "channel-$it" }, ids)
        ids = RecentChannels.opened(ids, "channel-30")
        assertEquals(50, ids.size)
        assertEquals("channel-30", ids.first())
        assertEquals(50, ids.distinct().size)
    }
    @Test fun availableHistoryUsesCurrentModelsAndKeepsChronologicalOrder() {
        val a = Channel("a", "test", "Обновлённое название", "fixture://a")
        val b = Channel("b", "test", "Канал Б", "fixture://b")
        assertEquals(listOf(b, a), RecentChannels.available(listOf("removed", "b", "a"), listOf(a, b)))
    }
}
