package tv.airloom.core

import org.junit.Assert.*
import org.junit.Test

class CatchupResolverTest {
    private val now = 1_800_000_000_000L
    private val programme = Programme("e", "s", "epg", "News", now - 7200_000, now - 3600_000)
    private val channel = Channel("c", "p", "News", "https://example.org/path%2Fkey/video.m3u8?token=a%2Fb&keep=yes", catchup = CatchupSpec("flussonic", days = 5), userAgent = "Airloom", referer = "https://example.org/")
    private fun stream(c: Channel = channel, p: Programme = programme): ResolvedStream = (CatchupResolver.resolve(PlaybackRequest.Archive(c, p), now) as CatchupResult.Available).stream

    @Test fun `flussonic keeps raw credential path and query`() {
        val result = stream()
        assertEquals("https://example.org/path%2Fkey/index-1799992800-3600.m3u8?token=a%2Fb&keep=yes", result.url)
        assertEquals("Airloom", result.headers["User-Agent"])
        assertTrue(result.archive)
    }
    @Test fun `ongoing Flussonic programme uses growing now window`() {
        assertTrue(stream(p = programme.copy(end = now + 10000)).url.contains("-now.m3u8"))
    }
    @Test fun `expired future invalid and unknown archives unavailable but live works`() {
        assertTrue(CatchupResolver.resolve(PlaybackRequest.Archive(channel, programme.copy(start = now - 6 * 86400_000L)), now) is CatchupResult.Unavailable)
        assertTrue(CatchupResolver.resolve(PlaybackRequest.Archive(channel, programme.copy(start = now + 1000, end = now + 10000)), now) is CatchupResult.Unavailable)
        assertTrue(CatchupResolver.resolve(PlaybackRequest.Archive(channel, programme.copy(end = programme.start)), now) is CatchupResult.Unavailable)
        val unknown = channel.copy(catchup = CatchupSpec("mystery", days = 5))
        assertTrue(CatchupResolver.resolve(PlaybackRequest.Archive(unknown, programme), now) is CatchupResult.Unavailable)
        assertTrue(CatchupResolver.resolve(PlaybackRequest.Live(unknown), now) is CatchupResult.Available)
    }
    @Test fun `append joins query and substitutes both template forms`() {
        val c = channel.copy(catchup = CatchupSpec("append", "?start=\${utc}&end={end}&duration={duration}", 5))
        val url = stream(c).url
        assertTrue(url.contains("&start=1799992800&end=1799996400&duration=3600"))
        assertFalse(url.contains('$'))
    }
    @Test fun `shift built in query and time correction`() {
        val c = channel.copy(catchup = CatchupSpec("shift", days = 5, correctionSeconds = 3600))
        assertTrue(stream(c).url.contains("utc=1799996400&lutc=1800000000"))
    }
    @Test fun `default relative template retains auth query and encodes catchup id`() {
        val c = channel.copy(catchup = CatchupSpec("default", "archive/{catchup-id}?start={utc}", 5))
        assertEquals("https://example.org/path%2Fkey/archive/my%20id%2Fa?start=1799992800&token=a%2Fb&keep=yes", stream(c, programme.copy(catchupId = "my id/a")).url)
    }
    @Test fun `different archive host never receives inherited access token`() {
        val c = channel.copy(catchup = CatchupSpec("default", "https://archive.example.org/play?start={utc}", 5))
        assertFalse(stream(c).url.contains("token="))
    }
    @Test fun `missing archive identifier or unknown placeholders fail clearly`() {
        val c = channel.copy(catchup = CatchupSpec("default", "https://example.org/{catchup-id}", 5))
        assertTrue(CatchupResolver.resolve(PlaybackRequest.Archive(c, programme), now) is CatchupResult.Unavailable)
        assertTrue(CatchupResolver.resolve(PlaybackRequest.Archive(c.copy(catchup = c.catchup.copy(source = "https://example.org/{unsupported}")), programme), now) is CatchupResult.Unavailable)
    }
}
