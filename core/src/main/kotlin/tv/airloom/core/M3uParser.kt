package tv.airloom.core

import java.io.Reader
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

/** Parses extended M3U without conflating provider EPG IDs with channel identity. */
object M3uParser {
    private val attribute = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))""")

    fun parse(reader: Reader, playlistId: String): ParsedPlaylist {
        var defaults = emptyMap<String, String>()
        var pending: Map<String, String>? = null
        var name = ""
        var group = ""
        var userAgent = ""
        var referer = ""
        var warnings = 0
        var validHeader = false
        val epgUrls = linkedSetOf<String>()
        val occurrences = mutableMapOf<String, Int>()
        val channels = mutableListOf<Channel>()
        reader.buffered().lineSequence().forEach { raw ->
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", true) -> {
                    validHeader = true
                    defaults = attributes(line)
                    listOf("url-tvg", "x-tvg-url", "tvg-url").forEach { key ->
                        defaults[key]?.split(Regex("[,;\\s]+"))?.filter { it.isNotBlank() }?.let(epgUrls::addAll)
                    }
                }
                line.startsWith("#EXTINF:", true) -> {
                    if (pending != null) warnings++
                    val separator = titleSeparator(line)
                    pending = attributes(if (separator >= 0) line.substring(0, separator) else line)
                    name = if (separator >= 0) line.substring(separator + 1).trim() else ""
                    userAgent = ""
                    referer = ""
                }
                line.startsWith("#EXTGRP:", true) -> group = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val key = line.substringAfter(':').substringBefore('=').lowercase(Locale.ROOT)
                    val value = line.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> userAgent = value
                        "http-referrer", "http-referer" -> referer = value
                    }
                }
                line.startsWith('#') -> Unit
                else -> {
                    val metadata = pending
                    if (metadata == null) { warnings++; return@forEach }
                    val url = line.substringBefore('|').trim()
                    if (!isStreamUri(url)) { warnings++; pending = null; return@forEach }
                    val attrs = defaults + metadata
                    val headers = parseHeaders(line.substringAfter('|', ""))
                    val occurrence = occurrences.getOrDefault(url, 0)
                    occurrences[url] = occurrence + 1
                    val days = listOf("catchup-days", "tvg-rec", "timeshift").firstNotNullOfOrNull { key ->
                        metadata[key]?.toDoubleOrNull()?.toInt()
                    } ?: listOf("catchup-days", "tvg-rec", "timeshift").firstNotNullOfOrNull { key ->
                        defaults[key]?.toDoubleOrNull()?.toInt()
                    } ?: 0
                    var mode = attrs["catchup"].orEmpty().lowercase(Locale.ROOT)
                    if (mode.isEmpty() && attrs["catchup-source"].orEmpty().isNotBlank()) mode = "default"
                    if (mode.isEmpty() && days > 0) mode = "shift"
                    val archiveDays = if (mode == "none" || mode == "disabled") 0 else days.coerceAtLeast(0)
                    val index = channels.size
                    channels += Channel(
                        id = stableId(playlistId, url, occurrence.toString()), playlistId = playlistId,
                        name = name.ifBlank { attrs["tvg-name"].orEmpty().ifBlank { "Канал ${index + 1}" } },
                        url = url, group = attrs["group-title"].orEmpty().ifBlank { group.ifBlank { "Без категории" } },
                        tvgId = attrs["tvg-id"].orEmpty(), tvgName = attrs["tvg-name"].orEmpty(),
                        logo = attrs["tvg-logo"].orEmpty(), number = attrs["tvg-chno"]?.toIntOrNull() ?: index + 1,
                        catchup = CatchupSpec(mode, attrs["catchup-source"].orEmpty(), archiveDays,
                            ((attrs["catchup-correction"]?.toDoubleOrNull() ?: 0.0) * 3600).toLong()),
                        userAgent = headers["user-agent"] ?: userAgent.ifBlank { attrs["user-agent"].orEmpty() },
                        referer = headers["referer"] ?: headers["referrer"] ?: referer.ifBlank { attrs["referer"].orEmpty() },
                        order = index, favouriteOrder = index,
                    )
                    pending = null
                }
            }
        }
        if (pending != null) warnings++
        require(validHeader) { "Файл не является плейлистом M3U: отсутствует заголовок #EXTM3U" }
        require(channels.isNotEmpty()) { "В плейлисте не найдено каналов с корректными адресами" }
        return ParsedPlaylist(channels, epgUrls.toList(), warnings)
    }

    private fun attributes(text: String): Map<String, String> = attribute.findAll(text).associate {
        it.groupValues[1].lowercase(Locale.ROOT) to (it.groups[2]?.value ?: it.groups[3]?.value ?: it.groupValues[4])
    }

    private fun titleSeparator(line: String): Int {
        var quote: Char? = null
        line.forEachIndexed { i, c ->
            if (quote != null) { if (c == quote) quote = null }
            else if (c == '"' || c == '\'') quote = c
            else if (c == ',') return i
        }
        return -1
    }

    private fun parseHeaders(text: String): Map<String, String> = text.split('&').mapNotNull { item ->
        val parts = item.split('=', limit = 2)
        if (parts.size != 2) null else runCatching {
            parts[0].lowercase(Locale.ROOT) to URLDecoder.decode(parts[1], "UTF-8")
        }.getOrNull()
    }.toMap()

    private fun isStreamUri(text: String): Boolean = runCatching {
        val uri = URI(text)
        uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https", "rtsp", "rtmp", "udp", "rtp") && !uri.rawAuthority.isNullOrBlank()
    }.getOrDefault(false)
}

internal fun stableId(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(32) { repeat(16) { i ->
        val value = digest[i].toInt() and 255
        append(hex[value ushr 4]); append(hex[value and 15])
    } }
}
