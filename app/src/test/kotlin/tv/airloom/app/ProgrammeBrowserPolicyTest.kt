package tv.airloom.app

import java.time.*
import org.junit.Assert.*
import org.junit.Test
import tv.airloom.core.*

class ProgrammeBrowserPolicyTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-10-04T12:00:00Z").toEpochMilli()
    private val channel = Channel("c", "p", "Test", "https://example.invalid/live/index.m3u8", catchup = CatchupSpec("flussonic", days = 3))
    private fun programme(id: String, from: Long, to: Long) = Programme(id, "epg", "c", id, from, to)

    @Test fun dayWindowUsesCalendarBoundariesAcrossDst() {
        for ((date, hours) in listOf("2026-03-29" to 23L, "2026-10-25" to 25L, "2026-12-31" to 24L)) {
            val day = LocalDate.parse(date)
            val (start, end) = ProgrammeBrowserPolicy.window(day, zone)
            assertEquals(hours * 3_600_000, end - start)
            assertEquals(day, ProgrammeBrowserPolicy.today(start, zone))
            assertEquals(day.plusDays(1), ProgrammeBrowserPolicy.today(end, zone))
        }
    }
    @Test fun depthIsRollingAndCappedAtImporterLimit() {
        assertEquals(LocalDate.parse("2026-10-01"), ProgrammeBrowserPolicy.earliest(channel, now, zone))
        assertEquals(ProgrammeBrowserPolicy.today(now, zone), ProgrammeBrowserPolicy.earliest(channel.copy(catchup = CatchupSpec(days = -1)), now, zone))
        assertEquals(ProgrammeBrowserPolicy.earliest(channel.copy(catchup = CatchupSpec("flussonic", days = 365)), now, zone),
            ProgrammeBrowserPolicy.earliest(channel.copy(catchup = CatchupSpec("flussonic", days = Int.MAX_VALUE)), now, zone))
    }
    @Test fun statusesRespectRealResolverIncludingCurrentAndBoundary() {
        assertEquals("С начала", ProgrammeBrowserPolicy.status(channel, programme("current", now - 1000, now + 1000), now))
        assertEquals("Архив", ProgrammeBrowserPolicy.status(channel, programme("edge", now - 3 * 86400_000L, now - 1000), now))
        assertEquals("Срок хранения этой передачи истёк", ProgrammeBrowserPolicy.status(channel, programme("old", now - 3 * 86400_000L - 1, now - 1000), now))
        assertEquals("Далее", ProgrammeBrowserPolicy.status(channel, programme("future", now, now + 1000), now))
        assertEquals("У передачи некорректное время", ProgrammeBrowserPolicy.status(channel, programme("bad", now - 1000, now - 2000), now))
        assertEquals("Этот формат архива пока не поддерживается", ProgrammeBrowserPolicy.status(channel.copy(catchup = CatchupSpec("unknown", days = 3)), programme("past", now - 2000, now - 1000), now))
    }
    @Test fun selectionRestoresIdentityThenNearestThenCurrentOrFirst() {
        val past = programme("past", now - 3000, now - 1000)
        val current = programme("current", now - 1000, now + 1000)
        val future = programme("future", now + 1000, now + 3000)
        val rows = listOf(past, current, future)
        val date = ProgrammeBrowserPolicy.today(now, zone)
        assertEquals(current, ProgrammeBrowserPolicy.select(rows, null, now, date, zone))
        assertEquals(past, ProgrammeBrowserPolicy.select(rows, past.copy(title = "old title"), now, date, zone))
        assertEquals(future, ProgrammeBrowserPolicy.select(listOf(past, future), current.copy(start = now - 500), now, date, zone))
        assertEquals(past, ProgrammeBrowserPolicy.select(rows, null, now, date.minusDays(1), zone))
        assertNull(ProgrammeBrowserPolicy.select(emptyList(), current, now, date, zone))
        assertFalse(ProgrammeBrowserPolicy.same(past, past.copy(sourceId = "other")))
    }
    @Test fun localMidnightAndMonthRolloverDifferFromUtc() {
        val instant = Instant.parse("2026-12-31T23:30:00Z").toEpochMilli()
        assertEquals(LocalDate.parse("2027-01-01"), ProgrammeBrowserPolicy.today(instant, zone))
        assertEquals(LocalDate.parse("2026-12-31"), ProgrammeBrowserPolicy.today(instant, ZoneOffset.UTC))
    }
    @Test fun noArchiveShowsOnlyCurrentAndFutureAndSupportedArchiveHidesExpiredRows() {
        val old = programme("old", now - 4 * 86400_000L, now - 4 * 86400_000L + 1000)
        val past = programme("past", now - 2000, now - 1000)
        val current = programme("current", now - 1000, now + 1000)
        val future = programme("future", now + 1000, now + 2000)
        val rows = listOf(old, past, current, future)
        assertEquals(listOf(past, current, future), ProgrammeBrowserPolicy.visible(channel, rows, now))
        for (spec in listOf(CatchupSpec(), CatchupSpec("none", days = 3), CatchupSpec("default", days = 3))) {
            val noArchive = channel.copy(catchup = spec)
            assertFalse(ProgrammeBrowserPolicy.hasArchive(noArchive))
            assertEquals(listOf(current, future), ProgrammeBrowserPolicy.visible(noArchive, rows, now))
            assertEquals(ProgrammeBrowserPolicy.today(now, zone), ProgrammeBrowserPolicy.earliest(noArchive, now, zone))
        }
    }
    @Test fun progressReportsElapsedAndRemainingAndClampsAtEdges() {
        val p = programme("p", now - 42 * 60_000, now + 28 * 60_000)
        assertEquals(ProgrammeProgress(.6f, 42, 28), ProgrammeBrowserPolicy.progress(p, now))
        assertEquals(ProgrammeProgress(0f, 0, 70), ProgrammeBrowserPolicy.progress(p, p.start - 1))
        assertEquals(ProgrammeProgress(1f, 70, 0), ProgrammeBrowserPolicy.progress(p, p.end + 1))
        assertEquals(1L, ProgrammeBrowserPolicy.progress(p, p.end - 1).remainingMinutes)
    }

}
