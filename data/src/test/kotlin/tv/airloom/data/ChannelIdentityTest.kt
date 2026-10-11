package tv.airloom.data

import org.junit.Assert.*
import org.junit.Test
import tv.airloom.core.*

class ChannelIdentityTest {
    private fun channel(id: String, url: String, name: String = "Канал", group: String = "Новости") = Channel(id, "playlist", name, url, group, tvgId = "shared")
    @Test fun changedTokenKeepsUniqueChannelIdentity() {
        val previous = listOf(channel("stable", "https://example.test/live?token=old"))
        val incoming = listOf(channel("parsed", "https://example.test/live?token=new"))
        assertEquals("stable", reconcileChannels(incoming, previous).single().id)
    }
    @Test fun repeatedTvgIdDoesNotCollapseHdAndSd() {
        val previous = listOf(channel("sd", "https://example.test/sd", "Канал"), channel("hd", "https://example.test/hd", "Канал HD"))
        val result = reconcileChannels(previous.map { it.copy(id = "new-" + it.id) }.reversed(), previous)
        assertEquals(listOf("hd", "sd"), result.map { it.id })
    }
    @Test fun ambiguousFallbackGetsNewIdentity() {
        val previous = listOf(channel("one", "https://example.test/1"), channel("two", "https://example.test/2"))
        val result = reconcileChannels(listOf(channel("new", "https://example.test/new")), previous)
        assertEquals("new", result.single().id)
    }
    @Test fun duplicatesAtSameUrlRemainSeparate() {
        val previous = listOf(channel("one", "https://example.test/live"), channel("two", "https://example.test/live"))
        val result = reconcileChannels(previous.map { it.copy(id = "parsed" + it.id) }, previous)
        assertEquals(2, result.map { it.id }.distinct().size)
        assertEquals(listOf("one", "two"), result.map { it.id })
    }
    @Test fun ambiguousEpgIdDoesNotChooseArbitrarySource() {
        val c = channel("channel", "https://example.test/live")
        assertNull(automaticBinding(c, listOf(EpgChannel("one", "shared", "Канал"), EpgChannel("two", "shared", "Канал"))))
    }
    @Test fun normalizedNameMatchesWithoutProviderId() {
        val c = channel("channel", "https://example.test/live").copy(tvgId = "", name = "Первый-канал")
        assertEquals("1", automaticBinding(c, listOf(EpgChannel("epg", "1", "Первый канал")))?.epgChannelId)
    }
}
