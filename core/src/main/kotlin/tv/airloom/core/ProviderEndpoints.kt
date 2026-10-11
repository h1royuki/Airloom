package tv.airloom.core

import java.net.URI

/** Provider URL rules live here, rather than in image or playback UI code. */
object ProviderEndpoints {
    private val hostPattern = Regex("^(https?://)([^/:?#]+)(?::[0-9]+)?(.*)$", RegexOption.IGNORE_CASE)
    private val knownHosts = setOf("iptv.online", "iptv.me", "iptv.pm")
    private val keyPattern = Regex("^[A-Za-z0-9_-]{4,128}$")
    private val playlistPattern = Regex("^/play/([^/]+)/m3u8/?$", RegexOption.IGNORE_CASE)

    fun key(input: String): String {
        val value = input.trim()
        val candidate = if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            val uri = runCatching { URI(value) }.getOrNull()
            uri?.rawPath?.let { playlistPattern.matchEntire(it)?.groupValues?.get(1) }.orEmpty()
        } else value
        require(keyPattern.matches(candidate)) { "Укажите ключ IPTV.ONLINE или ссылку на плейлист." }
        return candidate
    }

    fun origin(input: String): String {
        val value = input.trim()
        require(value.isNotEmpty()) { "Укажите домен." }
        val uri = runCatching { URI(if ("://" in value) value else "https://$value") }.getOrNull()
        require(uri != null && uri.host != null && uri.host.isNotBlank() &&
            uri.scheme.lowercase() in setOf("https", "http") && uri.userInfo == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") && uri.rawQuery == null && uri.rawFragment == null
        ) { "Укажите домен без пути и параметров." }
        return "${uri.scheme.lowercase()}://${uri.host.lowercase()}${if (uri.port >= 0) ":${uri.port}" else ""}"
    }

    fun normalized(profile: ProviderProfile): ProviderProfile = profile.copy(
        key = key(profile.key),
        baseOrigin = origin(profile.baseOrigin),
        playlistOrigin = profile.playlistOrigin.takeIf(String::isNotBlank)?.let(::origin).orEmpty(),
        epgOrigin = profile.epgOrigin.takeIf(String::isNotBlank)?.let(::origin).orEmpty(),
        logoOrigin = profile.logoOrigin.takeIf(String::isNotBlank)?.let(::origin).orEmpty(),
        streamOrigin = profile.streamOrigin.takeIf(String::isNotBlank)?.let(::origin).orEmpty(),
    )

    fun playlist(profile: ProviderProfile): String = "${profile.playlistOrigin.ifBlank { profile.baseOrigin }}/play/${profile.key}/m3u8"
    fun epg(profile: ProviderProfile): String = "${profile.epgOrigin.ifBlank { profile.baseOrigin }}/epg/${profile.epgVariant.fileName}"

    fun logo(url: String, profile: ProviderProfile): String = replaceHost(url, profile.logoOrigin.ifBlank { profile.baseOrigin }, profile)
    fun stream(url: String, profile: ProviderProfile): String =
        if (profile.streamOrigin.isBlank()) url else replaceHost(url, profile.streamOrigin, profile)

    fun streamRewriter(profile: ProviderProfile): (String) -> String {
        if (profile.streamOrigin.isBlank()) return { it }
        val hosts = knownHosts + listOf(profile.baseOrigin, profile.playlistOrigin, profile.epgOrigin, profile.logoOrigin, profile.streamOrigin)
            .filter(String::isNotBlank).mapNotNull { runCatching { URI(it).host?.lowercase() }.getOrNull() }
        return { url ->
            val match = hostPattern.matchEntire(url)
            if (match != null && match.groupValues[2].lowercase() in hosts) profile.streamOrigin + match.groupValues[3] else url
        }
    }

    private fun replaceHost(url: String, targetOrigin: String, profile: ProviderProfile): String {
        val match = hostPattern.matchEntire(url) ?: return url
        val host = match.groupValues[2].lowercase()
        val configuredHosts = listOf(profile.baseOrigin, profile.playlistOrigin, profile.epgOrigin, profile.logoOrigin, profile.streamOrigin)
            .filter(String::isNotBlank).mapNotNull { runCatching { URI(it).host?.lowercase() }.getOrNull() }
        if (host !in knownHosts && host !in configuredHosts) return url
        return targetOrigin + match.groupValues[3]
    }
}
