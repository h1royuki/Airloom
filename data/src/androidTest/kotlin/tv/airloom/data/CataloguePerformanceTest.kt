package tv.airloom.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import tv.airloom.core.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/** Provider-sized synthetic data, private temporary database, no network or user data. */
class CataloguePerformanceTest {
    @Test fun cursorSearchAtProviderScale() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "scale-${UUID.randomUUID()}.db"
        val prefsFile = File(context.cacheDir, "$name.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val queries = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        val db = Room.databaseBuilder(context, AirloomDatabase::class.java, name)
            .setQueryCallback({ _, _ -> queries.incrementAndGet() }, executor).build()
        val now = System.currentTimeMillis()
        try {
            val dao = db.dao()
            val channels = (0 until 2_000).map { i -> Channel("ch$i", "scale", "Канал $i", "https://example.invalid/$i/live.m3u8",
                group = "Категория ${i % 20}", catchup = CatchupSpec("shift", days = 7), epgSourceId = "e", epgChannelId = "ch$i") }
            db.withTransaction {
                dao.putPlaylist(PlaylistSource("scale", "Synthetic", "fixture", SourceKind.FILE).entity())
                dao.putChannels(channels.map { it.entity() })
                dao.putEpgSource(EpgSource("e", "scale", "fixture", SourceKind.FILE).entity("g"))
                dao.putEpgChannels(channels.map { EpgChannelEntity("e", "g", it.id, it.name) })
                dao.putBindings(channels.map { BindingEntity(it.id, "e", it.id, true) })
                for (batch in 0 until 500) {
                    dao.putProgrammes((0 until 1_000).map { n ->
                        val i = batch * 1_000 + n
                        val start = now - (i + 1) * 1_000L
                        ProgrammeEntity("e", "g", "p%06d".format(i), "ch${i % 2_000}",
                            if (i >= 499_900) "Искомая передача" else "Обычная передача", start, start + 500,
                            "Синтетическое описание передачи. ".repeat(3), "", "")
                    })
                }
            }
            fun heap() = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            var baselineHeap = heap()
            var baselinePages = 0
            var baselineHits = 0
            queries.set(0)
            val baselineMs = withContext(Dispatchers.IO) { measureTimeMillis {
                var offset = 0
                while (baselineHits < 100 && baselinePages < 20) {
                    val page = mutableListOf<ProgrammeEntity>()
                    db.openHelper.readableDatabase.query(
                        "SELECT DISTINCT p.* FROM programmes p JOIN epg_sources s ON s.id = p.sourceId AND s.generation = p.generation JOIN bindings b ON b.sourceId = p.sourceId AND b.epgChannelId = p.channelId JOIN channels c ON c.id = b.channelId WHERE c.active = 1 AND p.start <= ? AND p.end > p.start AND c.playlistId = 'scale' ORDER BY p.start DESC, p.sourceId, p.id LIMIT 500 OFFSET ?",
                        arrayOf(now, offset)).use { cursor ->
                        while (cursor.moveToNext()) page += ProgrammeEntity(
                            cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4),
                            cursor.getLong(5), cursor.getLong(6), cursor.getString(7), cursor.getString(8), cursor.getString(9))
                    }
                    baselinePages++
                    baselineHeap = maxOf(baselineHeap, heap())
                    baselineHits += page.count { it.title.contains("искомая", ignoreCase = true) }
                    if (page.size < 500) break
                    offset += page.size
                }
            } }
            val baselineQueries = queries.get()
            System.gc()
            queries.set(0)
            val store = PreferenceDataStoreFactory.create(scope = scope) { prefsFile }
            val repo = RoomAirloomRepository(context, db, PreferencesStore(store), OkHttpClient())
            var results: List<Pair<Channel, Programme>> = emptyList()
            val optimizedMs = measureTimeMillis { results = repo.searchProgrammes("искомая", "scale", now) }
            assertEquals(100, results.size)
            assertEquals(500_000, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM programmes").use { it.moveToFirst(); it.getInt(0) })
            println("SYNTHETIC_SCALE channels=2000 programmes=500000 baselineMs=$baselineMs optimizedMs=$optimizedMs baselineSampledRows=${baselinePages * 500} baselineHits=$baselineHits baselinePages=$baselinePages baselineQueries=$baselineQueries optimizedQueries=${queries.get()} baselinePeakHeapBytes=$baselineHeap optimizedFinalHeapBytes=${heap()}")
        } finally {
            scope.cancel(); db.close(); executor.shutdown(); context.deleteDatabase(name); prefsFile.delete()
        }
    }
}
