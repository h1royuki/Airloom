package tv.airloom.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.airloom.core.Channel
import tv.airloom.core.EpgBinding
import tv.airloom.core.EpgChannel

class EpgMatcherTest {
    @Test fun indexedMatchesPreservePreviousAmbiguityAndAliasRules() {
        val candidates = listOf(
            EpgChannel("first", "shared", "Первый"),
            EpgChannel("second", "shared", "Другой"),
            EpgChannel("first", "news-a", "Новости HD"),
            EpgChannel("first", "news-b", "Новости-HD"),
            EpgChannel("first", "unique", "Культура"),
            EpgChannel("first", "alias", "ТВ Центр"),
            EpgChannel("first", "alias", "ТВЦ"),
            EpgChannel("first", "punctuation", "---")
        )
        val inputs = listOf(
            Channel("ambiguous-id", "p", "Первый", "", tvgId = "shared"),
            Channel("ambiguous-name", "p", "Новости HD", ""),
            Channel("exact-wins", "p", "Новости HD", "", tvgId = "unique"),
            Channel("unique-name", "p", "КУЛЬТУРА", "", tvgId = "missing"),
            Channel("deduplicated-aliases", "p", "ТВЦ", "", tvgName = "ТВ Центр"),
            Channel("ambiguous-aliases", "p", "Культура", "", tvgName = "ТВ Центр"),
            Channel("unknown", "p", "Нет такого канала", ""),
            Channel("punctuation-name", "p", "...", ""),
            Channel("empty-name", "p", "", "")
        )
        val matcher = EpgMatcher(candidates)
        inputs.forEach { channel -> assertEquals(channel.id, referenceMatch(channel, candidates), matcher.binding(channel)) }
        assertNull(matcher.binding(inputs[0]))
        assertNull(matcher.binding(inputs[1]))
        assertEquals("unique", matcher.binding(inputs[2])?.epgChannelId)
        assertEquals("alias", matcher.binding(inputs[4])?.epgChannelId)
    }

    @Test fun reusedIndexKeepsSourcesDistinctAcrossLargeUnmatchedChannelSet() {
        val candidates = (0 until 2000).map { EpgChannel("source", "epg-$it", "Телевизионный канал $it") }
        val matcher = EpgMatcher(candidates)
        repeat(2308) { index ->
            val channel = Channel("channel-$index", "playlist", "Телевизионный-канал-$index", "")
            assertEquals(if (index < candidates.size) "epg-$index" else null, matcher.binding(channel)?.epgChannelId)
        }
    }

    // The former scan-based behavior is kept only as an independent parity oracle.
    private fun referenceMatch(channel: Channel, candidates: List<EpgChannel>): EpgBinding? {
        val exact = candidates.filter { channel.tvgId.isNotBlank() && it.id == channel.tvgId }
        val match = if (exact.isNotEmpty()) exact.singleOrNull() else {
            val names = listOf(channel.tvgName, channel.name).filter { it.isNotBlank() }.map(::normalizeEpgName).toSet()
            candidates.filter { normalizeEpgName(it.name) in names }.distinctBy { it.sourceId to it.id }.singleOrNull()
        }
        return match?.let { EpgBinding(channel.id, it.sourceId, it.id) }
    }
}
