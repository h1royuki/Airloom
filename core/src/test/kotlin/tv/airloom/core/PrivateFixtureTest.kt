package tv.airloom.core

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in local acceptance. Never copies private input into reports or source control. */
class PrivateFixtureTest {
    @Test fun `parse private provider snapshot with counts only`() {
        val directory = System.getenv("AIRLOOM_PRIVATE_FIXTURES_DIR")
        assumeTrue("Private fixtures not requested", !directory.isNullOrBlank())
        val root = File(requireNotNull(directory))
        val started = System.nanoTime()
        val playlist = File(root, "provider.m3u").bufferedReader().use { M3uParser.parse(it, "private-test") }
        val m3uMillis = (System.nanoTime() - started) / 1_000_000
        val epgStarted = System.nanoTime()
        var callbacks = 0
        val epg = File(root, "provider.xml.gz").inputStream().use {
            XmltvParser.parse(it, "private-epg", onProgramme = { callbacks++ })
        }
        val epgMillis = (System.nanoTime() - epgStarted) / 1_000_000
        assertTrue("Playlist must contain channels", playlist.channels.isNotEmpty())
        assertTrue("EPG must contain programmes", epg.programmes > 0)
        assertTrue("Every programme delivered", callbacks == epg.programmes)
        println("Private fixture: channels=${playlist.channels.size}, groups=${playlist.channels.map { it.group }.distinct().size}, archives=${playlist.channels.count { it.catchup.days > 0 }}, playlistWarnings=${playlist.warnings}, m3uMs=$m3uMillis, epgChannels=${epg.channels}, programmes=${epg.programmes}, epgSkipped=${epg.skipped}, epgMs=$epgMillis")
    }
}
