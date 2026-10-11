package tv.airloom.core

import org.junit.Assert.*
import org.junit.Test

class ProviderEndpointsTest {
    private val profile = ProviderProfile(key = "TESTKEY1234", baseOrigin = "https://iptv.online")

    @Test fun acceptsKeyOrProviderPlaylistUrl() {
        assertEquals("TESTKEY1234", ProviderEndpoints.key(" TESTKEY1234 "))
        assertEquals("TESTKEY1234", ProviderEndpoints.key("https://iptv.online/play/TESTKEY1234/m3u8"))
        assertEquals("https://iptv.online/play/TESTKEY1234/m3u8", ProviderEndpoints.playlist(profile))
        assertThrows(IllegalArgumentException::class.java) { ProviderEndpoints.key("https://other.test/play/TESTKEY1234/other") }
    }

    @Test fun normalizesAndRejectsNonOriginInputs() {
        assertEquals("https://iptv.me", ProviderEndpoints.origin("IPTV.ME"))
        assertEquals("http://iptv.me:8080", ProviderEndpoints.origin("http://iptv.me:8080/"))
        assertThrows(IllegalArgumentException::class.java) { ProviderEndpoints.origin("https://iptv.me/epg/epg.xml") }
        assertThrows(IllegalArgumentException::class.java) { ProviderEndpoints.origin("https://user@iptv.me") }
    }

    @Test fun buildsEveryEpgVariant() {
        val files = listOf("epg.xml.gz", "epg.xml", "epg-lite.xml.gz", "epg-lite.xml")
        assertEquals(files, EpgVariant.entries.map { ProviderEndpoints.epg(profile.copy(epgOrigin = "https://iptv.me", epgVariant = it)).substringAfterLast('/') })
        assertEquals("https://iptv.me/epg/epg.xml.gz", ProviderEndpoints.epg(profile.copy(epgOrigin = "https://iptv.me")))
    }

    @Test fun rewritesProviderLogosUsingExplicitDomainWithoutMirrorFallback() {
        val custom = profile.copy(logoOrigin = "https://images.example.test")
        assertEquals("https://images.example.test/images/ico/channel.png?q=1", ProviderEndpoints.logo("https://iptv.pm/images/ico/channel.png?q=1", custom))
        assertEquals("https://other.example.test/logo.png", ProviderEndpoints.logo("https://other.example.test/logo.png", custom))
    }

    @Test fun streamsStayAsPublishedUntilOverridden() {
        val url = "http://iptv.me/live/TESTKEY1234/index.m3u8?token=abc%2Fdef"
        assertEquals(url, ProviderEndpoints.stream(url, profile))
        val custom = profile.copy(streamOrigin = "https://stream.example.test")
        assertEquals("https://stream.example.test/live/TESTKEY1234/index.m3u8?token=abc%2Fdef", ProviderEndpoints.stream(url, custom))
        assertEquals("https://stream.example.test/live/{start}/{duration}.m3u8", ProviderEndpoints.stream("https://iptv.online/live/{start}/{duration}.m3u8", custom))
        assertEquals("https://cdn.example.test/live.m3u8", ProviderEndpoints.stream("https://cdn.example.test/live.m3u8", custom))
    }
}
