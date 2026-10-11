package tv.airloom.core

import org.junit.Assert.*
import org.junit.Test

class SearchResultsTest {
    private val now = 1_700_000_000_000L
    private val a = Channel("a", "p", "Матч", "https://example.invalid/live/index.m3u8", order = 2, catchup = CatchupSpec("flussonic", days = 2))
    private val b = a.copy(id = "b", name = "Спорт", order = 1)
    private fun programme(id: String, from: Long, to: Long) = Programme(id, "epg", "one", "Матч", from, to)
    @Test fun liveMatchesUnionDeduplicatesAndLoadsCurrentProgramme() {
        val live = programme("live", now - 1000, now + 1000)
        val result = SearchResultBuilder.build("Матч", listOf(a,b), listOf(a to live, b to live), mapOf(a.id to listOf(live)), now)
        assertEquals(listOf("b", "a"), result.live.map { it.channel.id })
        assertTrue(result.live.all { it.programme == live })
        assertTrue(result.archive.isEmpty())
        assertNull(SearchResultBuilder.build("Матч", listOf(a), emptyList(), emptyMap(), now).live.single().programme)
    }
    @Test fun onlyFinishedAvailableArchiveAndTimeBoundaries() {
        val ended = programme("ended", now - 10_000, now)
        val future = programme("future", now + 1, now + 5000)
        val live = programme("live", now, now + 1000)
        val expired = programme("expired", now - 3 * 86_400_000L, now - 2 * 86_400_000L)
        val bad = programme("bad", now, now)
        val noArchive = a.copy(id = "no", catchup = CatchupSpec())
        val hidden = a.copy(id = "hidden", hidden = true)
        val unsupported = a.copy(id = "unsupported", catchup = CatchupSpec("unknown", days = 2))
        val result = SearchResultBuilder.build("Матч", listOf(a,noArchive,hidden,unsupported),
            listOf(a to ended, a to ended, a to future, a to live, a to expired, a to bad, noArchive to ended, hidden to ended, unsupported to ended), emptyMap(), now)
        assertEquals(listOf("ended"), result.archive.map { it.second.id })
        assertFalse(result.live.any { it.channel.id == "hidden" })
        assertEquals("live", result.live.first { it.channel.id == "a" }.programme?.id)
        val after = SearchResultBuilder.build("Матч", listOf(a), listOf(a to live), emptyMap(), now + 1000)
        assertNull(after.live.single().programme)
        assertEquals("live", after.archive.single().second.id)
    }
    @Test fun emptyQueryAndLimitsAndArchiveOrder() {
        assertTrue(SearchResultBuilder.build(" ", listOf(a), emptyList(), emptyMap(), now).live.isEmpty())
        assertEquals(100, SearchResultBuilder.build("Матч", (0..120).map { a.copy(id = "$it", order = it) }, emptyList(), emptyMap(), now).live.size)
        val old = programme("old", now - 2000, now - 1000)
        val recent = programme("new", now - 1000, now)
        assertEquals(listOf("new", "old"), SearchResultBuilder.build("Матч", listOf(a), listOf(a to old, a to recent), emptyMap(), now).archive.map { it.second.id })
    }
}
