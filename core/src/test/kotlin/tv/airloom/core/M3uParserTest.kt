package tv.airloom.core

import org.junit.Assert.*
import org.junit.Test

class M3uParserTest {
    @Test fun `header archive mode requires days and preserves duplicate EPG identifiers`() {
        val source = """#EXTM3U catchup="flussonic" url-tvg="https://example.org/a.xml.gz,https://example.org/b.xml"
#EXTINF:-1 tvg-id="news" tvg-rec="5" timeshift="5" tvg-name="News, HD",Новости HD
#EXTGRP:Новости
https://example.org/hd/video.m3u8
#EXTINF:-1 tvg-id="news",Новости
https://example.org/sd/video.m3u8
"""
        val parsed = M3uParser.parse(source.reader(), "p")
        assertEquals(2, parsed.channels.size)
        assertEquals(2, parsed.epgUrls.size)
        assertEquals("Новости HD", parsed.channels[0].name)
        assertEquals("Новости", parsed.channels[0].group)
        assertEquals(5, parsed.channels[0].catchup.days)
        assertEquals("flussonic", parsed.channels[1].catchup.mode)
        assertEquals(0, parsed.channels[1].catchup.days)
        assertNotEquals(parsed.channels[0].id, parsed.channels[1].id)
        assertEquals(parsed, M3uParser.parse(source.reader(), "p"))
    }

    @Test fun `BOM per channel overrides and URL headers`() {
        val parsed = M3uParser.parse(("\uFEFF" + """#EXTM3U catchup="flussonic" catchup-days="7"
#EXTINF:-1 catchup="append" catchup-days="0" catchup-source="?s={utc}" group-title="Спорт",One
#EXTVLCOPT:http-user-agent=VLC
#EXTVLCOPT:http-referrer=https://example.org/
https://example.org/live.m3u8|User-Agent=Custom%20Agent&Referer=https%3A%2F%2Fexample.org%2Fpage
#EXTINF:-1,Two
https://example.org/second.m3u8
""").reader(), "a")
        val first = parsed.channels.first()
        assertEquals(0, first.catchup.days)
        assertEquals("append", first.catchup.mode)
        assertEquals("Custom Agent", first.userAgent)
        assertEquals("https://example.org/page", first.referer)
        assertEquals("https://example.org/live.m3u8", first.url)
        assertEquals("", parsed.channels[1].userAgent)
        assertEquals(7, parsed.channels[1].catchup.days)
    }

    @Test fun `invalid entries skipped repeated urls unique and reorder stable`() {
        val parsed = M3uParser.parse("""#EXTM3U
#EXTINF:-1,Lost
#EXTINF:-1,Bad
not a URL
#EXTINF:-1,A
https://example.org/live
#EXTINF:-1,A again
https://example.org/live
#EXTINF:-1,Dangling
""".reader(), "p")
        assertEquals(3, parsed.warnings)
        assertNotEquals(parsed.channels[0].id, parsed.channels[1].id)
    }

    @Test(expected = IllegalArgumentException::class) fun `html error is not a playlist`() {
        M3uParser.parse("<html>Error</html>".reader(), "p")
    }

    @Test fun `local zero disables inherited archive days`() {
        val channel = M3uParser.parse("""#EXTM3U catchup="flussonic" catchup-days="7"
#EXTINF:-1 tvg-rec="0",No archive
https://example.org/live
""".reader(), "p").channels.single()
        assertEquals(0, channel.catchup.days)
    }
}
