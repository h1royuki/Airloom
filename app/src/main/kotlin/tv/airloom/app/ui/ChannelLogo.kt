package tv.airloom.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import coil.compose.AsyncImage
import coil.decode.DataSource
import coil.request.CachePolicy
import coil.request.ImageRequest
import tv.airloom.core.Channel
import tv.airloom.core.ProviderEndpoints
import tv.airloom.core.ProviderProfile
import java.net.URI

internal val LocalProviderProfile = compositionLocalOf<ProviderProfile?> { null }

/** Logos use the configured provider domain and a private 30-day disk cache. */
@Composable
internal fun ChannelLogo(
    channel: Channel?,
    enabled: Boolean,
    size: Int,
    modifier: Modifier = Modifier,
    playlistLocation: String? = null,
) {
    val context = LocalContext.current
    val provider = LocalProviderProfile.current
    val candidates = remember(channel?.logo, channel?.epgLogo, playlistLocation, provider) { logoCandidates(channel, playlistLocation, provider) }
    var candidateIndex by remember(candidates) { mutableIntStateOf(0) }
    var expiryTick by remember(candidates) { mutableIntStateOf(0) }
    val url = candidates.getOrNull(candidateIndex)
    // Hashing and the expiry store stay off the main thread; known logos resolve synchronously.
    var cacheEntry by remember(context, url, channel?.userAgent, channel?.referer, expiryTick) {
        mutableStateOf(url?.let { LogoDiskCache.cached(it, channel) })
    }
    LaunchedEffect(context, url, channel?.userAgent, channel?.referer, expiryTick) {
        if (cacheEntry == null && url != null) cacheEntry = LogoDiskCache.resolve(context, url, channel)
    }
    LaunchedEffect(cacheEntry) {
        cacheEntry?.let {
            kotlinx.coroutines.delay((it.expiresAt - System.currentTimeMillis()).coerceAtLeast(1))
            expiryTick++
        }
    }
    val fade = motionDuration(Motion.Swap)
    val request = remember(context, url, channel?.userAgent, channel?.referer, cacheEntry, size, fade) {
        val entry = cacheEntry
        if (url != null && entry != null) logoImageRequest(context, url, channel, size, entry.key, fade) else null
    }
    var loaded by remember(candidates) { mutableStateOf(false) }
    // A logo read from disk or the network comes in over its letter; one already in memory replaces it at once,
    // so scrolling through known channels shows no fades. The letter's alpha is read only while drawing.
    val instant = remember(candidates) { booleanArrayOf(false) }
    val letter = remember(candidates) { Animatable(1f) }
    LaunchedEffect(letter, loaded) {
        if (!loaded) letter.snapTo(1f) else if (instant[0]) letter.snapTo(0f) else letter.animateTo(0f, tween(fade))
    }
    val letterVisible by remember(letter) { derivedStateOf { letter.value > 0f } }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (!enabled || !loaded || letterVisible) Label(channel?.name?.take(1) ?: "TV", Modifier.graphicsLayer { alpha = if (enabled) letter.value else 1f }, size = size, color = LocalAccent.current)
        if (enabled && request != null) AsyncImage(
            model = request,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            onSuccess = { instant[0] = it.result.dataSource == DataSource.MEMORY_CACHE; loaded = true },
            onError = { loaded = false; if (candidateIndex + 1 < candidates.size) candidateIndex++ },
        )
    }
}

internal fun logoCandidates(channel: Channel?, playlistLocation: String?, provider: ProviderProfile? = null): List<String> = channel?.let {
    val playlistLogo = resolveLogo(it.logo, playlistLocation)
    val epgLogo = resolveLogo(it.epgLogo, null)
    listOfNotNull(playlistLogo, epgLogo).map { url -> provider?.let { profile -> ProviderEndpoints.logo(url, profile) } ?: url }.distinct()
}.orEmpty()

internal fun logoImageRequest(context: Context, url: String, channel: Channel?, size: Int = 0, cacheKey: String = LogoDiskCache.entry(context, url, channel).key, crossfadeMs: Int = 0): ImageRequest =
    ImageRequest.Builder(context).data(url).crossfade(crossfadeMs)
        .apply {
            channel?.userAgent?.takeIf(String::isNotBlank)?.let { addHeader("User-Agent", it) }
            channel?.referer?.takeIf(String::isNotBlank)?.let { addHeader("Referer", it) }
        }
        .memoryCacheKey("$cacheKey:$size")
        .diskCacheKey(cacheKey)
        .diskCachePolicy(CachePolicy.ENABLED)
        .build()

internal fun resolveLogo(raw: String, playlistLocation: String?): String? {
    val value = raw.trim().replace(" ", "%20")
    if (value.isEmpty()) return null
    return runCatching {
        val uri = URI(value)
        val resolved = if (uri.isAbsolute) uri else playlistLocation?.let { URI(it).resolve(uri) }
        resolved?.takeIf { it.scheme.equals("http", true) || it.scheme.equals("https", true) }?.toString()
    }.getOrNull()
}
