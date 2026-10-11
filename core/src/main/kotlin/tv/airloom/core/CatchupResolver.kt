package tv.airloom.core

import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Provider archive URLs are separate from a player's live seek window. Times are milliseconds. */
object CatchupResolver {
    fun resolve(request: PlaybackRequest, now: Long = System.currentTimeMillis()): CatchupResult {
        val channel = request.channel
        val headers = buildMap {
            channel.userAgent.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            channel.referer.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        }
        if (request is PlaybackRequest.Live) return CatchupResult.Available(ResolvedStream(channel.url, headers))
        request as PlaybackRequest.Archive
        val programme = request.programme
        val spec = channel.catchup
        if (spec.days <= 0) return unavailable("Провайдер не заявил архив для этого канала")
        if (programme.end <= programme.start) return unavailable("У передачи некорректное время")
        if (programme.start >= now) return unavailable("Передача ещё не началась")
        if (programme.start < now - spec.days.toLong() * 86_400_000L) return unavailable("Срок хранения этой передачи истёк")
        val start = programme.start / 1000 + spec.correctionSeconds
        val end = minOf(programme.end, now) / 1000 + spec.correctionSeconds
        val duration = (end - start).coerceAtLeast(1)
        val nowSeconds = now / 1000
        val liveUri = runCatching { URI(channel.url) }.getOrNull() ?: return unavailable("Некорректный адрес потока")
        if (liveUri.scheme?.lowercase(Locale.ROOT) !in listOf("http", "https")) return unavailable("Архив этого протокола пока не поддерживается")
        val mode = spec.mode.lowercase(Locale.ROOT)
        val template = spec.source
        val rawUrl = when (mode) {
            "flussonic" -> if (template.isNotBlank()) template else {
                val path = liveUri.rawPath.orEmpty()
                val suffix = if (programme.end > now) "now" else duration.toString()
                val extension = if (path.endsWith(".ts", true)) "ts" else "m3u8"
                val base = if (path.substringAfterLast('/').contains('.')) path.substringBeforeLast('/', "") else path.trimEnd('/')
                val file = if (extension == "ts") "timeshift_abs-$start.ts" else "index-$start-$suffix.m3u8"
                "${liveUri.scheme}://${liveUri.rawAuthority}$base/$file" + liveUri.rawQuery?.let { "?$it" }.orEmpty()
            }
            "default" -> template.ifBlank { return unavailable("В плейлисте отсутствует адрес архива") }
            "append" -> {
                if (template.isBlank()) return unavailable("В плейлисте отсутствует шаблон архива")
                appendTemplate(channel.url, template)
            }
            "shift" -> if (template.isNotBlank()) appendTemplate(channel.url, template)
                else appendTemplate(channel.url, "?utc={utc}&lutc={lutc}")
            else -> return unavailable("Этот формат архива пока не поддерживается")
        }
        var resolved = rawUrl
        val placeholders = mapOf(
            "utc" to start.toString(), "start" to start.toString(), "end" to end.toString(),
            "utcend" to end.toString(), "duration" to duration.toString(), "offset" to (nowSeconds - start).toString(),
            "lutc" to nowSeconds.toString(), "timestamp" to nowSeconds.toString(),
            "catchup-id" to encode(programme.catchupId),
        )
        placeholders.forEach { (key, value) -> resolved = resolved.replace("\${$key}", value).replace("{$key}", value) }
        val instant = Instant.ofEpochSecond(start)
        mapOf("Y" to "yyyy", "m" to "MM", "d" to "dd", "H" to "HH", "M" to "mm", "S" to "ss").forEach { (key, pattern) ->
            resolved = resolved.replace("{$key}", DateTimeFormatter.ofPattern(pattern).withZone(ZoneOffset.UTC).format(instant))
        }
        if (Regex("\\{[^}]+\\}").containsMatchIn(resolved)) return unavailable("В адресе архива есть неподдерживаемые параметры")
        if (template.contains("catchup-id") && programme.catchupId.isBlank()) return unavailable("В телепрограмме отсутствует идентификатор архива")
        val uri = runCatching { liveUri.resolve(resolved) }.getOrNull() ?: return unavailable("Некорректный адрес архива")
        if (uri.scheme?.lowercase(Locale.ROOT) !in listOf("http", "https") || uri.rawAuthority.isNullOrBlank()) return unavailable("Некорректный адрес архива")
        // Retain authentication query parameters on the same origin only; never leak them to a different server.
        val url = if (uri.rawAuthority == liveUri.rawAuthority && uri.scheme == liveUri.scheme) mergeQuery(uri, liveUri) else uri.toASCIIString()
        return CatchupResult.Available(ResolvedStream(url, headers, archive = true))
    }

    private fun appendTemplate(live: String, template: String): String {
        if (template.startsWith("http://") || template.startsWith("https://")) return template
        if (template.startsWith('?') || template.startsWith('&')) {
            val base = live.substringBefore('#')
            return base + (if ('?' in base) "&" else "?") + template.drop(1)
        }
        return live + template
    }

    private fun mergeQuery(target: URI, source: URI): String {
        val existing = target.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }
        val keys = existing.map { it.substringBefore('=') }.toSet()
        val inherited = source.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() && it.substringBefore('=') !in keys }
        val query = (existing + inherited).joinToString("&")
        return "${target.scheme}://${target.rawAuthority}${target.rawPath.orEmpty()}" +
            query.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty() +
            target.rawFragment?.let { "#$it" }.orEmpty()
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun unavailable(reason: String) = CatchupResult.Unavailable(reason)
}
