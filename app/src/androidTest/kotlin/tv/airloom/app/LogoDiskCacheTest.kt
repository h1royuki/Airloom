package tv.airloom.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.airloom.app.ui.LogoDiskCache
import tv.airloom.app.ui.logoImageRequest
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class LogoDiskCacheTest {
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    @Test fun applicationUsesPrivateBoundedLogoCache() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val disk = requireNotNull(Coil.imageLoader(context).diskCache)
        assertEquals(context.filesDir.resolve("channel_logos").absolutePath, disk.directory.toString())
        assertEquals(64L * 1024 * 1024, disk.maxSize)
    }

    @Test fun keyIsPrivateAndExpiresAfterThirtyDays() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val url = "https://example.invalid/logo-${UUID.randomUUID()}.png?token=private"
        val now = System.currentTimeMillis()
        val first = LogoDiskCache.entry(context, url, null, now)
        assertFalse(first.key.contains("token"))
        assertFalse(first.key.contains("example.invalid"))
        assertEquals(first.key, LogoDiskCache.entry(context, url, null, now + LogoDiskCache.TTL_MS - 1).key)
        val renewed = LogoDiskCache.entry(context, url, null, now + LogoDiskCache.TTL_MS)
        assertNotEquals(first.key, renewed.key)
        LogoDiskCache.pruneExpired(context, renewed.expiresAt)
    }

    @Test fun logoCanBeReadFromDiskWhenNetworkIsGone() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val server = ServerSocket(0)
        val hits = AtomicInteger()
        val job = launch(Dispatchers.IO) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) Unit
                    val headers = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(headers.toByteArray()); write(bytes); flush() }
                    hits.incrementAndGet()
                }
            } catch (_: java.net.SocketException) { /* Closing the fixture server ends the loop. */ }
        }
        val directory = context.cacheDir.resolve("logo-cache-test-${UUID.randomUUID()}")
        val loader = ImageLoader.Builder(context)
            .diskCache(DiskCache.Builder().directory(directory).maxSizeBytes(4L * 1024 * 1024).build())
            .respectCacheHeaders(false).build()
        try {
            val url = "http://127.0.0.1:${server.localPort}/logo.png"
            val key = LogoDiskCache.entry(context, url, null).key
            val request = logoImageRequest(context, url, null, 18, key)
            assertEquals(DataSource.NETWORK, (loader.execute(request) as SuccessResult).dataSource)
            assertEquals(1, hits.get())
            loader.memoryCache?.clear()
            server.close()
            assertEquals(DataSource.DISK, (loader.execute(request) as SuccessResult).dataSource)
            assertEquals(1, hits.get())
        } finally {
            server.close()
            job.cancel()
            loader.shutdown()
            directory.deleteRecursively()
        }
    }
}
