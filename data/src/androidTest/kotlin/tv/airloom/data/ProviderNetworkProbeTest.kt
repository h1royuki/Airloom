package tv.airloom.data

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assume
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import tv.airloom.core.M3uParser
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Opt-in on-device diagnostic. The fixture stays private, and neither URLs nor response bodies
 * are emitted. This checks origin response compatibility, not successful media playback.
 */
@Ignore("Manual network diagnostic: excluded from validation to prevent provider requests.")
@RunWith(AndroidJUnit4::class)
class ProviderNetworkProbeTest {
    @Test fun probeProviderResponsesFromLocalFixture(): Unit {
        Assume.assumeTrue("Network probes require explicit opt-in", InstrumentationRegistry.getArguments().getString("networkProbe") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = listOf(File(context.getExternalFilesDir("import"), "provider.m3u"), File(context.filesDir, "import/provider.m3u")).firstOrNull { it.isFile }
        Assume.assumeTrue("Optional private provider fixture is not present", fixture != null)
        val parsed = requireNotNull(fixture).bufferedReader().use { M3uParser.parse(it, "network-probe") }
        val epg = parsed.epgUrls.firstOrNull()
        val channel = parsed.channels.firstOrNull()
        Assume.assumeTrue("Fixture must provide EPG and a channel", epg != null && channel != null)
        val base = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val variants = listOf(
            Variant("airloom-default", "Airloom/0.1", base),
            Variant("browser-default", "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36", base.newBuilder().build()),
            Variant("airloom-http1", "Airloom/0.1", base.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build())
        )
        variants.forEach { variant ->
            probe("epg", requireNotNull(epg), "", variant)
            probe("live", requireNotNull(channel).url, channel.referer, variant)
        }
        variants.forEach { it.client.connectionPool.evictAll(); it.client.dispatcher.executorService.shutdown() }
    }

    @Test fun probeEpgAlternativeClientsFromLocalFixture(): Unit {
        Assume.assumeTrue("Network probes require explicit opt-in", InstrumentationRegistry.getArguments().getString("networkProbe") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = listOf(File(context.getExternalFilesDir("import"), "provider.m3u"), File(context.filesDir, "import/provider.m3u")).firstOrNull { it.isFile }
        Assume.assumeTrue("Optional private provider fixture is not present", fixture != null)
        val epg = requireNotNull(fixture).bufferedReader().use { M3uParser.parse(it, "network-probe") }.epgUrls.firstOrNull()
        Assume.assumeTrue("Fixture must provide HTTPS EPG", epg?.startsWith("https://", ignoreCase = true) == true)
        val address = requireNotNull(epg)
        val http1 = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1)).build()
        try {
            probe("epg", address, "", Variant("browser-http1", "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36", http1))
            var connection: HttpURLConnection? = null
            try {
                val active = URL(address).openConnection() as HttpURLConnection
                connection = active
                active.connectTimeout = 15_000
                active.readTimeout = 15_000
                active.instanceFollowRedirects = true
                active.setRequestProperty("User-Agent", "Airloom/0.1")
                val code = active.responseCode
                Log.i(TAG, "variant=urlconnection target=epg status=$code contentType=${safeHeader(active.contentType)} server=${safeHeader(active.getHeaderField("Server"))}")
                runCatching { (if (code >= 400) active.errorStream else active.inputStream)?.use { it.read(ByteArray(64)) } }
            } catch (_: Exception) {
                Log.i(TAG, "variant=urlconnection target=epg status=unavailable contentType=unknown server=unknown")
            } finally { connection?.disconnect() }
            // Diagnostic only: production clients never downgrade a configured HTTPS source.
            val original = address.toHttpUrl()
            val plain = original.newBuilder().scheme("http").port(if (original.port == 443) 80 else original.port).build()
            try {
                val request = Request.Builder().url(plain).header("User-Agent", "Airloom/0.1").build()
                http1.newCall(request).execute().use { response ->
                    Log.i(TAG, "variant=explicit-http target=epg status=${response.code} finalScheme=${response.request.url.scheme}")
                    runCatching { response.body?.byteStream()?.read(ByteArray(64)) }
                }
            } catch (_: Exception) { Log.i(TAG, "variant=explicit-http target=epg status=unavailable finalScheme=unknown") }
        } finally { http1.connectionPool.evictAll(); http1.dispatcher.executorService.shutdown() }
    }

    private fun probe(target: String, location: String, referer: String, variant: Variant) {
        try {
            val request = Request.Builder().url(location)
                .header("User-Agent", variant.userAgent)
                .apply { if (referer.isNotBlank()) header("Referer", referer) }
                .build()
            variant.client.newCall(request).execute().use { response ->
                Log.i(TAG, "variant=${variant.label} target=$target status=${response.code} contentType=${safeHeader(response.header("Content-Type"))} server=${safeHeader(response.header("Server"))}")
                // Only inspect stream availability; never log or retain media/EPG response bytes.
                runCatching { response.body?.byteStream()?.read(ByteArray(64)) }
            }
        } catch (_: Exception) {
            // The exception message/cause can contain a private URL or network address.
            Log.i(TAG, "variant=${variant.label} target=$target status=unavailable contentType=unknown server=unknown")
        }
    }

    private fun safeHeader(value: String?): String = value.orEmpty().take(100)
        .replace(Regex("[^A-Za-z0-9 .;=+/_()\\-]"), "_").ifBlank { "unknown" }
    private data class Variant(val label: String, val userAgent: String, val client: OkHttpClient)
    private companion object { const val TAG = "AirloomNetworkProbe" }
}
