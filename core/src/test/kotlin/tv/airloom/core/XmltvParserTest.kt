package tv.airloom.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

class XmltvParserTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tv SYSTEM "https://unreachable.invalid/xmltv.dtd">
<tv><channel id="news"><display-name>Новости &amp; мир</display-name></channel>
<programme channel="news" start="20260929120000 +0300" stop="20260929130000 +0300" catchup-id="episode"><title>Новости</title><desc>Описание</desc><category>News</category></programme>
<programme channel="news" start="bad" stop="20260929130000 +0300"><title>Invalid</title></programme></tv>"""

    @Test fun `stream gzip parse programmes and skip malformed dates`() {
        val compressed = ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { it.write(xml.toByteArray()) } }.toByteArray()
        val channels = mutableListOf<EpgChannel>()
        val programmes = mutableListOf<Programme>()
        val result = XmltvParser.parse(compressed.inputStream(), "s", 30, channels::add, programmes::add)
        assertEquals(XmltvParseResult(1, 1, 1), result)
        assertEquals("Новости & мир", channels.single().name)
        assertEquals(Instant.parse("2026-09-29T09:30:00Z").toEpochMilli(), programmes.single().start)
        assertEquals("episode", programmes.single().catchupId)
        assertEquals("Описание", programmes.single().description)
    }
    @Test fun `channel icons are read and channel-only scan stops before programmes`() {
        val xml = """<tv><channel id="one"><display-name>Первый</display-name><icon src="https://example.test/logo.png"/></channel><programme channel="one" start="bad" stop="bad"><title>Не читаем</title></programme></tv>"""
        val channels = mutableListOf<EpgChannel>()
        assertEquals(1, XmltvParser.parseChannels(xml.byteInputStream(), "epg", channels::add))
        assertEquals("https://example.test/logo.png", channels.single().icon)
        assertEquals("Первый", channels.single().name)
    }
    @Test fun `missing offset is UTC regardless device timezone and bad calendar date rejected`() {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals(Instant.parse("2026-09-29T12:00:00Z").toEpochMilli(), XmltvParser.parseTime("20260929120000"))
            assertEquals(Instant.parse("2026-09-29T12:00:00Z").toEpochMilli(), XmltvParser.parseTime("202609291200 +0000"))
            assertNull(XmltvParser.parseTime("20260230120000 +0000"))
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun `internal entities rejected`() {
        val hostile = """<!DOCTYPE tv [<!ENTITY attack "payload">]><tv><channel id="a"><display-name>&attack;</display-name></channel></tv>"""
        try { XmltvParser.parse(hostile.byteInputStream(), "s", onProgramme = {}); fail("Entity accepted") } catch (_: java.io.IOException) { }
    }
    @Test fun `external entities never read or exposed`() {
        val hostile = """<!DOCTYPE tv [<!ENTITY attack SYSTEM "file:///nonexistent-airloom-secret">]><tv><channel id="a"><display-name>&attack;</display-name></channel></tv>"""
        try { XmltvParser.parse(hostile.byteInputStream(), "s", onProgramme = {}); fail("External entity accepted") } catch (_: java.io.IOException) { }
    }
    @Test fun `entity guard works across read boundaries and UTF16`() {
        val hostile = """<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE tv [<!ENTITY attack "payload">]><tv/>"""
        val bytes = hostile.toByteArray(Charsets.UTF_16)
        val stream = object : java.io.ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(1, length))
        }
        try { XmltvParser.parse(stream, "s", onProgramme = {}); fail("Entity accepted") } catch (_: java.io.IOException) { }
    }
    @Test fun `callbacks stream before document complete and caller retains stream ownership`() {
        var count = 0
        var closed = false
        val stream = object : java.io.ByteArrayInputStream(xml.toByteArray()) { override fun close() { closed = true; super.close() } }
        XmltvParser.parse(stream, "s", onProgramme = { count++ })
        assertEquals(1, count)
        assertFalse(closed)
    }
    @Test fun `many programmes delivered individually with unique stable ids`() {
        val source = buildString {
            append("<tv>")
            repeat(10_000) { i -> append("<programme channel=\"$i\" start=\"20260929120000 Z\" stop=\"20260929130000 Z\"><title>Show $i</title></programme>") }
            append("</tv>")
        }
        val ids = mutableSetOf<String>()
        val result = XmltvParser.parse(source.byteInputStream(), "s", onProgramme = { ids += it.id })
        assertEquals(10_000, result.programmes)
        assertEquals(10_000, ids.size)
    }
    @Test fun `programmes outside the accepted window are counted but not delivered`() {
        val source = buildString {
            append("<tv>")
            repeat(4) { hour -> append("<programme channel=\"c\" start=\"202609291${hour}0000 Z\" stop=\"202609291${hour + 1}0000 Z\"><title>Show $hour</title></programme>") }
            append("</tv>")
        }
        val windowStart = requireNotNull(XmltvParser.parseTime("20260929120000 Z"))
        val windowEnd = requireNotNull(XmltvParser.parseTime("20260929130000 Z"))
        val seen = mutableListOf<Long>()
        val delivered = mutableListOf<Programme>()
        // A 30-minute offset shifts every programme before the window check.
        val result = XmltvParser.parse(source.byteInputStream(), "s", 30, accept = { start, end -> seen += start; end > windowStart && start < windowEnd }, onProgramme = delivered::add)
        assertEquals(4, result.programmes)
        assertEquals(4, seen.size)
        assertEquals(listOf("Show 1", "Show 2"), delivered.map { it.title })
        assertEquals(seen[1], delivered.first().start)
    }
    @Test fun `cached timestamps retain accepted formats invalid dates and per parse offsets`() {
        val times = listOf("20260929", "2026092901", "202609290130", "20260929013015", "20260929013015 +0300", "20260929013015 -04:30", "20260929013015Z", " 20260929013015 +0000 ")
        val source = buildString {
            append("<tv>")
            repeat(3) { round ->
                times.forEachIndexed { index, time ->
                    append("<programme channel=\"$round-$index\" start=\"$time\" stop=\"20261001000000 Z\"><title>Show</title></programme>")
                }
                append("<programme channel=\"invalid\" start=\"20260230120000 Z\" stop=\"20261001000000 Z\"/>")
            }
            append("</tv>")
        }
        listOf(0, 90, -30).forEach { offset ->
            val programmes = mutableListOf<Programme>()
            val result = XmltvParser.parse(source.byteInputStream(), "source", offset, onProgramme = programmes::add)
            assertEquals(24, result.programmes)
            assertEquals(3, result.skipped)
            programmes.forEachIndexed { index, programme ->
                assertEquals(requireNotNull(XmltvParser.parseTime(times[index % times.size])) + offset * 60_000L, programme.start)
            }
        }
    }

    @Test fun `large unique timestamp window and revisited old slot preserve programme times and ids`() {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withZone(java.time.ZoneOffset.UTC)
        val base = Instant.parse("2026-09-29T00:00:00Z")
        val source = buildString {
            append("<tv>")
            // More distinct timestamp spellings than the per-parse cache can retain.
            (0..9000).plus(0).forEach { index ->
                val start = formatter.format(base.plusSeconds(index.toLong()))
                val stop = formatter.format(base.plusSeconds(index.toLong() + 1))
                append("<programme channel=\"a\" start=\"$start Z\" stop=\"$stop Z\"><title>Show</title></programme>")
            }
            append("</tv>")
        }
        var first: Programme? = null
        var last: Programme? = null
        var count = 0
        val result = XmltvParser.parse(source.byteInputStream(), "source", onProgramme = { programme ->
            if (first == null) first = programme
            val expected = if (count <= 9000) count else 0
            assertEquals(base.toEpochMilli() + expected * 1000L, programme.start)
            assertEquals(programme.start + 1000L, programme.end)
            last = programme
            count++
        })
        assertEquals(9002, result.programmes)
        assertEquals(first, last)
    }

}
