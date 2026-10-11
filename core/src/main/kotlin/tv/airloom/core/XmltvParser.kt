package tv.airloom.core

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.StringReader
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory

data class XmltvParseResult(val channels: Int, val programmes: Int, val skipped: Int)

/** Bounded-memory XMLTV reader. Callbacks run synchronously, allowing transactional batch writes. */
object XmltvParser {
    private val timestamp = Regex("^(\\d{8}|\\d{10}|\\d{12}|\\d{14})(?:\\s*([+-]\\d{2}:?\\d{2}|Z))?$")
    private val formatter = DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)
    private val programmeFields = setOf("title", "desc", "category", "catchup-id")
    private val predefinedEntities = setOf("amp", "lt", "gt", "apos", "quot", "[dtd]")
    private const val MAX_CACHED_TIMESTAMPS = 8192

    fun parse(
        input: InputStream,
        sourceId: String,
        offsetMinutes: Int = 0,
        onChannel: (EpgChannel) -> Unit = {},
        onProgramme: (Programme) -> Unit,
    ): XmltvParseResult = parseInternal(input, sourceId, offsetMinutes, onChannel, onProgramme, false)

    /** Read the leading channel metadata without traversing a large programme catalogue. */
    fun parseChannels(input: InputStream, sourceId: String, onChannel: (EpgChannel) -> Unit): Int =
        parseInternal(input, sourceId, 0, onChannel, {}, true).channels

    private fun parseInternal(
        input: InputStream,
        sourceId: String,
        offsetMinutes: Int,
        onChannel: (EpgChannel) -> Unit,
        onProgramme: (Programme) -> Unit,
        stopAfterChannels: Boolean,
    ): XmltvParseResult {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false; isValidating = false }
        // Some Android parsers do not expose every Xerces feature. EntityResolver and lexical
        // handler below provide a second independent guard when a feature is unavailable.
        listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities", "http://apache.org/xml/features/nonvalidating/load-external-dtd").forEach {
            runCatching { factory.setFeature(it, false) }
        }
        val parser = factory.newSAXParser().xmlReader
        val handler = Handler(sourceId, offsetMinutes, onChannel, onProgramme, stopAfterChannels)
        parser.contentHandler = handler
        parser.errorHandler = handler
        parser.entityResolver = handler
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        runCatching { parser.setProperty("http://xml.org/sax/properties/declaration-handler", handler) }
        val unowned = object : FilterInputStream(input) { override fun close() = Unit }
        val buffered = BufferedInputStream(unowned)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        val decoded = if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered) else buffered
        decoded.use {
            try { parser.parse(InputSource(EntityGuardInputStream(it))) }
            catch (_: ChannelsComplete) { /* The first programme follows the channel header. */ }
        }
        require(handler.seenRoot) { "Файл не является телепрограммой XMLTV" }
        return XmltvParseResult(handler.channels, handler.programmes, handler.skipped)
    }

    private class ChannelsComplete : SAXException()

    /** XMLTV without an offset is interpreted as UTC, never the importing device's timezone. */
    fun parseTime(value: String): Long? = runCatching {
        val match = timestamp.matchEntire(value.trim()) ?: return null
        val local = LocalDateTime.parse(match.groupValues[1].padEnd(14, '0'), formatter)
        val offset = match.groupValues[2].ifBlank { "Z" }
        local.toInstant(ZoneOffset.of(offset)).toEpochMilli()
    }.getOrNull()

    /** Android Expat omits declaration/startEntity callbacks. Reject entity declarations in
     * the raw stream before SAX sees them; zero bytes are ignored to cover UTF-16/32 XML. */
    private class EntityGuardInputStream(input: InputStream) : FilterInputStream(input) {
        private val marker = "<!ENTITY".toByteArray(Charsets.US_ASCII)
        private var matched = 0
        private fun check(value: Int) {
            if (value <= 0) return
            matched = if (value == marker[matched].toInt()) matched + 1 else if (value == marker[0].toInt()) 1 else 0
            if (matched == marker.size) throw java.io.IOException("XML-сущности не поддерживаются")
        }
        override fun read(): Int = `in`.read().also(::check)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = `in`.read(buffer, offset, length)
            if (count > 0) for (i in offset until offset + count) check(buffer[i].toInt() and 255)
            return count
        }
    }

    private class Handler(
        val source: String,
        val offsetMinutes: Int,
        val onChannel: (EpgChannel) -> Unit,
        val onProgramme: (Programme) -> Unit,
        val stopAfterChannels: Boolean,
    ) : DefaultHandler2() {
        var seenRoot = false
        var channels = 0
        var programmes = 0
        var skipped = 0
        var depth = 0
        var channelId = ""
        var channelName = ""
        var channelIcon = ""
        var inChannel = false
        var inProgramme = false
        var start: Long? = null
        var end: Long? = null
        var title = ""
        var description = ""
        var category = ""
        var catchupId = ""
        var field: String? = null
        var fieldDepth = 0
        val text = StringBuilder()
        private val offsetMillis = offsetMinutes.toLong() * 60_000
        // EPG channels reuse the same broadcast-slot timestamps. Keep the expensive strict
        // conversion once per recent spelling, including invalid timestamps. This cache is
        // owned by one parse and bounded even for feeds with unique times for every entry.
        private val timestamps = object : LinkedHashMap<String, Long?>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long?>?): Boolean = size > MAX_CACHED_TIMESTAMPS
        }
        private fun cachedTime(value: String): Long? {
            val cached = timestamps[value]
            if (cached != null || timestamps.containsKey(value)) return cached
            return parseTime(value).also { timestamps[value] = it }
        }

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            depth++
            if (depth > 64) throw SAXException("Слишком глубокая вложенность XMLTV")
            if (depth == 1) {
                if (qName != "tv") throw SAXException("Ожидался корневой элемент tv")
                seenRoot = true
            }
            if (depth == 2) when (qName) {
                "channel" -> { inChannel = true; channelId = attributes.getValue("id").orEmpty(); channelName = ""; channelIcon = "" }
                "programme" -> {
                    if (stopAfterChannels && channels > 0) throw ChannelsComplete()
                    inProgramme = true
                    channelId = attributes.getValue("channel").orEmpty()
                    start = cachedTime(attributes.getValue("start").orEmpty())
                    end = cachedTime(attributes.getValue("stop").orEmpty())
                    title = ""; description = ""; category = ""
                    catchupId = attributes.getValue("catchup-id").orEmpty()
                }
            }
            if (depth == 3 && inChannel && qName == "icon" && channelIcon.isEmpty()) {
                channelIcon = attributes.getValue("src").orEmpty().trim()
            }
            if (depth == 3 && ((inChannel && qName == "display-name") || (inProgramme && qName in programmeFields))) {
                field = qName; fieldDepth = depth; text.setLength(0)
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (field != null) {
                if (text.length + length > 1_048_576) throw SAXException("Слишком длинное поле телепрограммы")
                text.append(ch, start, length)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (field != null && depth == fieldDepth) {
                val value = text.toString().trim()
                when (field) {
                    "display-name" -> if (channelName.isEmpty()) channelName = value
                    "title" -> if (title.isEmpty()) title = value
                    "desc" -> if (description.isEmpty()) description = value
                    "category" -> if (category.isEmpty()) category = value
                    "catchup-id" -> if (catchupId.isEmpty()) catchupId = value
                }
                field = null; text.setLength(0)
            }
            if (depth == 2 && qName == "channel") {
                if (channelId.isNotBlank()) { onChannel(EpgChannel(source, channelId, channelName.ifBlank { channelId }, channelIcon)); channels++ }
                else skipped++
                inChannel = false
            }
            if (depth == 2 && qName == "programme") {
                val from = start
                val until = end
                if (channelId.isNotBlank() && from != null && until != null && until > from) {
                    onProgramme(Programme(stableId(source, channelId, from.toString(), until.toString()), source, channelId,
                        title.ifBlank { "Без названия" }, from + offsetMillis, until + offsetMillis, description, category, catchupId))
                    programmes++
                } else skipped++
                inProgramme = false
            }
            depth--
        }

        // An ordinary external xmltv.dtd declaration is fine; it is never fetched.
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))
        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource = InputSource(StringReader(""))
        override fun internalEntityDecl(name: String?, value: String?) { throw SAXException("XML-сущности не поддерживаются") }
        override fun startEntity(name: String?) {
            if (name !in predefinedEntities) throw SAXException("XML-сущности не поддерживаются")
        }
        override fun fatalError(e: org.xml.sax.SAXParseException) { throw SAXException("Некорректный XMLTV", e) }
    }
}
