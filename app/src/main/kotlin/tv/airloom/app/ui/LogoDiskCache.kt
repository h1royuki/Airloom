package tv.airloom.app.ui

import android.content.Context
import coil.Coil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.airloom.core.Channel
import java.security.MessageDigest

/** Private Coil cache keys contain only hashes; entries are replaced after 30 days. */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
internal object LogoDiskCache {
    const val TTL_MS = 30L * 24 * 60 * 60 * 1_000
    private const val PREFS = "logo_cache_expiry"
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Entry(val key: String, val expiresAt: Long)

    // Resolved entries by their raw identity: composition reads them without hashing or disk access.
    private val resolved = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private fun identitySource(url: String, channel: Channel?) = url + "\u0000" + channel?.userAgent.orEmpty() + "\u0000" + channel?.referer.orEmpty()

    /** Main-safe lookup of an entry resolved earlier in this process. */
    fun cached(url: String, channel: Channel?, now: Long = System.currentTimeMillis()): Entry? =
        resolved[identitySource(url, channel)]?.takeIf { it.expiresAt > now && it.expiresAt <= now + TTL_MS }

    suspend fun resolve(context: Context, url: String, channel: Channel?): Entry =
        cached(url, channel) ?: withContext(Dispatchers.IO) { entry(context, url, channel) }

    @Synchronized
    fun entry(context: Context, url: String, channel: Channel?, now: Long = System.currentTimeMillis()): Entry {
        val source = identitySource(url, channel)
        val identity = digest(source)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldExpiry = prefs.getLong(identity, 0L)
        val expiry = if (oldExpiry > now && oldExpiry <= now + TTL_MS) oldExpiry else now + TTL_MS
        if (expiry != oldExpiry) {
            prefs.edit().putLong(identity, expiry).apply()
            if (oldExpiry > 0) cleanup.launch {
                runCatching { Coil.imageLoader(context.applicationContext).diskCache?.remove(cacheKey(identity, oldExpiry)) }
            }
        }
        return Entry(cacheKey(identity, expiry), expiry).also { resolved[source] = it }
    }

    suspend fun pruneExpired(context: Context, now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val expired = prefs.all.mapNotNull { (identity, value) ->
            (value as? Long)?.takeIf { it <= now || it > now + TTL_MS }?.let { identity to it }
        }
        if (expired.isEmpty()) return@withContext
        val disk = Coil.imageLoader(context.applicationContext).diskCache
        expired.forEach { (identity, expiry) ->
            val removed = synchronized(this@LogoDiskCache) {
                if (prefs.getLong(identity, 0L) == expiry) {
                    prefs.edit().remove(identity).apply()
                    true
                } else false
            }
            if (removed) runCatching { disk?.remove(cacheKey(identity, expiry)) }
        }
    }

    private fun cacheKey(identity: String, expiry: Long) = "logo-$identity-$expiry"

    private fun digest(value: String): String = buildString(64) {
        val hex = "0123456789abcdef"
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).forEach { byte ->
            val number = byte.toInt() and 255
            append(hex[number ushr 4]); append(hex[number and 15])
        }
    }
}
