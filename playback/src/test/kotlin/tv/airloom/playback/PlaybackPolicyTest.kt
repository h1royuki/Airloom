package tv.airloom.playback

import org.junit.Assert.*
import org.junit.Test
import tv.airloom.core.*

class PlaybackPolicyTest {
    private val start = 1_700_000_000_000L
    private val channel = Channel("channel", "source", "Test", "https://example.test/live/index.m3u8?token=fixture",
        catchup = CatchupSpec("flussonic", days = 5), userAgent = "fixture")
    private val request = PlaybackRequest.Archive(channel,
        Programme("programme", "epg", "station", "Test programme", start, start + 3_600_000))

    @Test fun serverSeekPreservesProgrammeEndAndOriginalRequest() {
        val shifted = PlaybackPolicy.requestAt(request, 600_000)
        assertEquals(start, request.programme.start)
        assertEquals(start + 600_000, shifted.programme.start)
        assertEquals(request.programme.end, shifted.programme.end)
        assertEquals(request.programme.title, shifted.programme.title)
        val resolved = CatchupResolver.resolve(shifted, start + 7_200_000) as CatchupResult.Available
        assertTrue(resolved.stream.url.contains("index-1700000600-3000.m3u8"))
        assertTrue(resolved.stream.url.endsWith("?token=fixture"))
        assertEquals("fixture", resolved.stream.headers["User-Agent"])
    }

    @Test fun unfinishedProgrammeCannotSeekIntoFuture() {
        val duration = PlaybackPolicy.availableDuration(request, start + 600_000)
        assertEquals(600_000L, duration)
        assertEquals(599_000L, PlaybackPolicy.clampArchivePosition(3_600_000, duration))
        assertEquals(0L, PlaybackPolicy.clampArchivePosition(-15_000, duration))
        assertEquals(0L, PlaybackPolicy.clampArchivePosition(15_000, 0))
    }

    @Test fun idOnlyArchivesDoNotOfferFakeServerSeeking() {
        val idOnly = request.copy(channel = channel.copy(catchup = CatchupSpec("default", "https://example.test/archive/{catchup-id}", 5)))
        assertFalse(PlaybackPolicy.canSeekOnServer(idOnly))
        assertTrue(PlaybackPolicy.canSeekOnServer(request))
        val timed = request.copy(channel = channel.copy(catchup = CatchupSpec("append", "?start={utc}", 5)))
        assertTrue(PlaybackPolicy.canSeekOnServer(timed))
        val dateOnly = request.copy(channel = channel.copy(catchup = CatchupSpec("default", "https://example.test/{Y}/{m}/{d}", 5)))
        assertFalse(PlaybackPolicy.canSeekOnServer(dateOnly))
    }

}
