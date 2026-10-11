package tv.airloom.app

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import tv.airloom.core.*
import tv.airloom.playback.AirloomPlayer

/** All HTTP traffic and footage are synthetic and confined to loopback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackNetworkIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun await(timeout: Long = 15000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for synthetic playback state", predicate())
    }
    private fun withPlayer(block: (AirloomPlayer, TestServer) -> Unit) {
        val file = File(context.cacheDir, "network-synthetic.mp4")
        syntheticVideo(file, 480)
        TestServer(fastStart(file.readBytes())).use { server ->
            lateinit var player: AirloomPlayer
            main { player = AirloomPlayer(context); player.configure(PlaybackNetworkSettings(retryDelayMs = 1000).withProfile(0)) }
            try { block(player, server) } finally { main { player.release() }; file.delete() }
        }
    }
    private fun channel(server: TestServer, path: String) = Channel(path, "synthetic", "Тест", server.url(path))

    @Test fun transientHttpRecoversAndPermanentAccessErrorStopsImmediately() = withPlayer { player, server ->
        main { player.play(PlaybackRequest.Live(channel(server, "flaky.mp4"))) }
        await(20000) { player.state.value.reconnectAttempts == 1 }
        await { player.state.value.isPlaying }
        assertNull(player.state.value.error)
        assertTrue(server.count("flaky.mp4") >= 5)
        main { player.play(PlaybackRequest.Live(channel(server, "blocked.mp4"))) }
        await { player.state.value.error != null }
        assertEquals(0, player.state.value.reconnectAttempts)
        assertEquals(1, server.count("blocked.mp4"))
        assertFalse(player.state.value.error!!.contains("http"))
    }

    @Test fun pauseStopAndSwitchCancelPendingRecovery() = withPlayer { player, server ->
        main {
            player.configure(PlaybackNetworkSettings(retryDelayMs = 5000).withProfile(0))
            player.play(PlaybackRequest.Live(channel(server, "failure.mp4")))
        }
        await { player.state.value.recovery != null }
        main { player.pause() }
        val count = server.count("failure.mp4")
        SystemClock.sleep(5500)
        assertEquals(count, server.count("failure.mp4"))
        assertFalse(player.state.value.playWhenReady)
        main { player.resume() }
        await { server.count("failure.mp4") > count }
        main { player.play(PlaybackRequest.Live(channel(server, "good.mp4"))) }
        await { player.state.value.isPlaying }
        val afterSwitch = server.count("failure.mp4")
        SystemClock.sleep(5500)
        assertEquals(afterSwitch, server.count("failure.mp4"))
        assertEquals("good.mp4", player.state.value.channel?.id)
        main { player.play(PlaybackRequest.Live(channel(server, "failure.mp4"))) }
        await { player.state.value.recovery != null }
        main { player.stop() }
        val stoppedCount = server.count("failure.mp4")
        SystemClock.sleep(5500)
        assertEquals(stoppedCount, server.count("failure.mp4"))
        assertNull(player.state.value.request)
    }

    @Test fun disabledRetriesAndExactReconnectLimit() = withPlayer { player, server ->
        main {
            player.configure(PlaybackNetworkSettings(reconnectAttempts = 0))
            player.play(PlaybackRequest.Live(channel(server, "failure.mp4")))
        }
        await { player.state.value.error != null }
        assertEquals(0, player.state.value.reconnectAttempts)
        assertEquals(4, server.count("failure.mp4"))
        main {
            player.configure(PlaybackNetworkSettings(reconnectAttempts = 1, retryDelayMs = 1000))
            player.retry()
        }
        await(20000) { player.state.value.error != null && player.state.value.reconnectAttempts == 1 }
        assertEquals(12, server.count("failure.mp4"))
    }

    @Test fun bufferingWatchdogConsumesSameBudget() = withPlayer { player, server ->
        main {
            player.configure(PlaybackNetworkSettings(reconnectAttempts = 1, retryDelayMs = 1000,
                readTimeoutMs = 60000, bufferingTimeoutMs = 30000))
            player.play(PlaybackRequest.Live(channel(server, "stall.mp4")))
        }
        await(35000) { player.state.value.recovery != null }
        assertNull(player.state.value.error)
        assertEquals(1, player.state.value.reconnectAttempts)
        await(35000) { player.state.value.error != null }
        assertEquals(1, player.state.value.reconnectAttempts)
        assertEquals(2, server.count("stall.mp4"))
    }

    @Test fun readTimeoutRecoversWithoutAWatchdogRestart() = withPlayer { player, server ->
        main {
            player.configure(PlaybackNetworkSettings(retryDelayMs = 1000, readTimeoutMs = 10000, bufferingTimeoutMs = 120000).withProfile(0))
            player.play(PlaybackRequest.Live(channel(server, "timeout.mp4")))
        }
        await(55000) { player.state.value.reconnectAttempts == 1 }
        await { player.state.value.isPlaying }
        assertNull(player.state.value.error)
    }

    @Test fun manualArchiveRetryPreservesNativeSeekPositionAndSettingsApplyOnRetry() = withPlayer { player, server ->
        val channel = channel(server, "good.mp4").copy(catchup = CatchupSpec("default", server.url("good.mp4") + "?id={catchup-id}", 5))
        val now = System.currentTimeMillis()
        val programme = Programme("test", "test", "test", "Архив", now - 60000, now - 40000, catchupId = "synthetic")
        main { player.play(PlaybackRequest.Archive(channel, programme)) }
        await { player.state.value.isPlaying }
        main { player.seekTo(5000); player.pause() }
        await { player.state.value.positionMs >= 5000 }
        main { player.configure(PlaybackNetworkSettings().withProfile(2)); player.retry() }
        await { player.state.value.isPlaying }
        assertTrue(player.state.value.positionMs >= 5000)
        assertTrue(player.state.value.positionMs < 10000)
    }

    @Test fun midStreamDisconnectAutomaticallyResumesArchiveAtSavedPosition() = withPlayer { player, server ->
        val channel = channel(server, "disconnect.mp4").copy(catchup = CatchupSpec("default", server.url("disconnect.mp4") + "?id={catchup-id}", 5))
        val now = System.currentTimeMillis()
        val programme = Programme("test", "test", "test", "Архив", now - 60000, now - 40000, catchupId = "synthetic")
        main { player.play(PlaybackRequest.Archive(channel, programme)) }
        await { player.state.value.isPlaying && player.state.value.positionMs >= 1000 }
        server.disconnect.countDown()
        await { player.state.value.recovery != null }
        val saved = player.state.value.positionMs
        assertTrue(saved >= 1000)
        await { player.state.value.isPlaying && player.state.value.recovery == null }
        assertNull(player.state.value.error)
        assertTrue(player.state.value.positionMs >= saved)
        assertEquals(1, player.state.value.reconnectAttempts)
    }

    @Test fun mediaSessionPauseAlsoPreventsDelayedRestart() = withPlayer { player, server ->
        main {
            player.configure(PlaybackNetworkSettings(retryDelayMs = 1000))
            player.play(PlaybackRequest.Live(channel(server, "failure.mp4")))
        }
        await { player.state.value.recovery != null }
        main { player.player.pause() }
        await { !player.state.value.playWhenReady }
        val count = server.count("failure.mp4")
        SystemClock.sleep(1500)
        assertEquals(count, server.count("failure.mp4"))
        main { player.player.play() }
        await { server.count("failure.mp4") > count }
        // A zero-delay resume must not leave a completed Job blocking subsequent retries.
        await { player.state.value.reconnectAttempts == 2 }
        await { server.count("failure.mp4") > count + 4 }
    }

    @Test fun releaseCancelsPendingRecovery() = withPlayer { player, server ->
        main {
            player.configure(PlaybackNetworkSettings(retryDelayMs = 5000))
            player.play(PlaybackRequest.Live(channel(server, "failure.mp4")))
        }
        await { player.state.value.recovery != null }
        val count = server.count("failure.mp4")
        main { player.release() }
        SystemClock.sleep(5500)
        assertEquals(count, server.count("failure.mp4"))
    }

    /** Move the synthetic MP4 metadata before media so it can play before the response completes. */
    private fun fastStart(bytes: ByteArray): ByteArray {
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        fun type(at: Int) = String(bytes, at + 4, 4, Charsets.US_ASCII)
        var cursor = 0
        var moov = -1
        var mdat = -1
        while (cursor + 8 <= bytes.size) {
            val rawSize = buffer.getInt(cursor)
            val size = if (rawSize == 1) buffer.getLong(cursor + 8).toInt() else if (rawSize == 0) bytes.size - cursor else rawSize
            require(size >= 8)
            if (type(cursor) == "moov") moov = cursor
            if (type(cursor) == "mdat") mdat = cursor
            cursor += size
        }
        require(moov >= 0 && mdat >= 0)
        if (moov < mdat) return bytes
        val moovSize = buffer.getInt(moov)
        val metadata = bytes.copyOfRange(moov, moov + moovSize)
        val edited = java.nio.ByteBuffer.wrap(metadata)
        fun patch(start: Int, end: Int) {
            var at = start
            while (at + 8 <= end) {
                val size = edited.getInt(at)
                require(size >= 8 && at + size <= end)
                when (String(metadata, at + 4, 4, Charsets.US_ASCII)) {
                    "moov", "trak", "mdia", "minf", "stbl" -> patch(at + 8, at + size)
                    "stco" -> repeat(edited.getInt(at + 12)) { i ->
                        val offset = at + 16 + i * 4
                        edited.putInt(offset, edited.getInt(offset) + moovSize)
                    }
                    "co64" -> repeat(edited.getInt(at + 12)) { i ->
                        val offset = at + 16 + i * 8
                        edited.putLong(offset, edited.getLong(offset) + moovSize)
                    }
                }
                at += size
            }
        }
        patch(0, metadata.size)
        return bytes.copyOfRange(0, mdat) + metadata + bytes.copyOfRange(mdat, moov) + bytes.copyOfRange(moov + moovSize, bytes.size)
    }

    private class TestServer(private val video: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        val disconnect = java.util.concurrent.CountDownLatch(1)
        private val requests = ConcurrentHashMap<String, AtomicInteger>()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        init {
            executor.execute {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    sockets.add(socket)
                    executor.execute { try { socket.use(::respond) } catch (_: Exception) { } finally { sockets.remove(socket) } }
                }
            }
        }
        fun url(path: String) = "http://127.0.0.1:${server.localPort}/$path"
        fun count(path: String) = requests[path]?.get() ?: 0
        private fun respond(socket: Socket) {
            socket.soTimeout = 5000
            val reader = socket.getInputStream().bufferedReader()
            val request = reader.readLine() ?: return
            val path = request.split(' ')[1].substringBefore('?').removePrefix("/")
            var range: Int? = null
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Range:", true)) range = line.substringAfter("bytes=").substringBefore('-').toIntOrNull()
            }
            val count = requests.getOrPut(path) { AtomicInteger() }.incrementAndGet()
            if (path == "stall.mp4" || (path == "timeout.mp4" && count <= 4)) { Thread.sleep(90000); return }
            val status = when {
                path == "blocked.mp4" -> 403
                path == "failure.mp4" || (path == "flaky.mp4" && count <= 4) || (path == "disconnect.mp4" && count in 2..4) -> 503
                else -> if (range != null) 206 else 200
            }
            val output = socket.getOutputStream()
            if (status >= 400) {
                output.write("HTTP/1.1 $status Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                val start = (range ?: 0).coerceIn(0, video.size - 1)
                val headers = "HTTP/1.1 $status OK\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: ${video.size - start}\r\n" +
                    (if (status == 206) "Content-Range: bytes $start-${video.size - 1}/${video.size}\r\n" else "") + "Connection: close\r\n\r\n"
                output.write(headers.toByteArray())
                if (path == "disconnect.mp4" && count == 1) {
                    output.write(video, start, (video.size - start) * 3 / 5)
                    output.flush()
                    disconnect.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    return
                }
                output.write(video, start, video.size - start)
            }
            output.flush()
        }
        override fun close() {
            server.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
        }
    }
}
