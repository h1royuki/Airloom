package tv.airloom.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.airloom.core.*
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RepositoryIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: AirloomDatabase
    private lateinit var repository: RoomAirloomRepository
    private lateinit var scope: CoroutineScope
    private lateinit var directory: File
    private lateinit var preferences: PreferencesStore
    private lateinit var preferenceFile: File
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AirloomDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        directory = File(context.filesDir, "import/test-${UUID.randomUUID()}").apply { mkdirs() }
        preferenceFile = File(context.cacheDir, "${UUID.randomUUID()}.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope) { preferenceFile }
        preferences = PreferencesStore(store)
        repository = RoomAirloomRepository(context, database, preferences, OkHttpClient())
    }
    @After fun teardown() { scope.cancel(); database.close(); directory.deleteRecursively(); preferenceFile.delete() }
    private fun playlist(url: String = "https://example.test/live"): PlaylistSource {
        val file = File(directory, "list.m3u")
        file.writeText("#EXTM3U\n#EXTINF:-1 tvg-id=\"one\" group-title=\"Новости\",Первый\n$url\n")
        return PlaylistSource("playlist", "Тест", file.path, SourceKind.FILE)
    }
    private fun epg(title: String): EpgSource {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
        val start = formatter.format(Instant.now().minusSeconds(300)); val end = formatter.format(Instant.now().plusSeconds(300))
        val file = File(directory, "epg.xml")
        file.writeText("<tv><channel id=\"one\"><display-name>Первый</display-name></channel><programme channel=\"one\" start=\"$start\" stop=\"$end\"><title>$title</title></programme></tv>")
        return EpgSource("epg", "playlist", file.path, SourceKind.FILE)
    }
    private suspend fun managementChannels(): List<Channel> {
        val source = playlist()
        File(source.location).writeText("#EXTM3U\n#EXTINF:-1 group-title=\"Кино\",Один\nhttps://example.invalid/a\n#EXTINF:-1 group-title=\"Кино\",Два\nhttps://example.invalid/b\n")
        repository.importPlaylist(source)
        return repository.channels("playlist", true).first()
    }
    @Test fun managementBatchRollsBackEntireTransactionAfterWriteFailure() = runBlocking {
        val channels = managementChannels()
        val secondId = channels[1].id.replace("'", "''")
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_fixture_hidden BEFORE UPDATE ON channel_preferences WHEN NEW.channelId = '$secondId' AND NEW.hidden = 1 BEGIN SELECT RAISE(ABORT, 'Synthetic failure'); END")
        try {
            repository.applyListMutation(ListMutation.Channels(linkedSetOf(channels[0].id, channels[1].id), hidden = true))
            fail("Expected transaction failure")
        } catch (_: android.database.SQLException) { }
        assertFalse(repository.channels("playlist", true).first().any { it.hidden })
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_fixture_hidden")
        repository.applyListMutation(ListMutation.Channels(channels.map { it.id }.toSet(), hidden = true, favourite = true))
        assertTrue(repository.channels("playlist", true).first().all { it.hidden && it.favourite })
    }
    @Test fun managementRestorationPreservesIndependentFlagsAndScopedOrder() = runBlocking {
        val channels = managementChannels()
        val ids = channels.map { it.id }
        val all = ChannelListScope("playlist", ChannelListKind.ALL)
        val category = ChannelListScope("playlist", ChannelListKind.CATEGORY, "Кино")
        repository.applyListMutation(ListMutation.Channels(setOf(ids[0]), hidden = true, favourite = true))
        repository.applyListMutation(ListMutation.Categories("playlist", setOf("Кино"), hidden = true, pinned = true))
        repository.applyListMutation(ListMutation.ChannelOrder(all, ids.reversed()))
        repository.applyListMutation(ListMutation.Pin(category, ids[0], true))
        repository.applyListMutation(ListMutation.Categories("playlist", setOf("Кино"), hidden = false))
        val restored = repository.channels("playlist", true).first()
        assertTrue(restored.first { it.id == ids[0] }.hidden)
        assertTrue(restored.first { it.id == ids[0] }.favourite)
        assertTrue(repository.categories("playlist").first().single().pinned)
        assertEquals(ids.reversed(), ChannelLists.ordered(restored, all, repository.channelListPreferences(all).first()).map { it.id })
        assertTrue(repository.channelListPreferences(category).first().single().pinned)
        assertFalse(repository.channelListPreferences(all).first().any { it.pinned })
        repository.applyListMutation(ListMutation.Channels(setOf(ids[0]), hidden = false))
        assertTrue(repository.channels("playlist", true).first().first { it.id == ids[0] }.favourite)
        val reopened = RoomAirloomRepository(context, database, preferences, OkHttpClient())
        assertEquals(repository.channelListPreferences(all).first(), reopened.channelListPreferences(all).first())
    }
    @Test fun managementRejectsStaleOrderWithoutOverwritingPreferences() = runBlocking {
        val channels = managementChannels()
        val scope = ChannelListScope("playlist", ChannelListKind.ALL)
        try {
            repository.applyListMutation(ListMutation.ChannelOrder(scope, listOf(channels[0].id)))
            fail("Expected stale catalogue rejection")
        } catch (_: IllegalArgumentException) { }
        assertTrue(repository.channelListPreferences(scope).first().isEmpty())
    }

    @Test fun searchExcludesFutureBeforeLimitAndHonoursPlaylist() = runBlocking {
        val source = playlist()
        val fileWithArchive = File(source.location)
        fileWithArchive.writeText(fileWithArchive.readText().replace("#EXTINF:-1", "#EXTINF:-1 catchup=\"flussonic\" catchup-days=\"2\""))
        repository.importPlaylist(source)
        val now = System.currentTimeMillis() / 1000 * 1000
        val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
        fun item(id: String, from: Long, to: Long) = "<programme channel=\"one\" start=\"${formatter.format(Instant.ofEpochMilli(from))}\" stop=\"${formatter.format(Instant.ofEpochMilli(to))}\"><title>Needle $id</title></programme>"
        val file = File(directory, "search.xml")
        file.writeText("<tv><channel id=\"one\"><display-name>Первый</display-name></channel>" +
            item("past", now - 120_000, now - 60_000) + item("live", now - 60_000, now + 60_000) +
            (1..110).joinToString("") { item("future$it", now + it * 60_000, now + (it + 1) * 60_000) } + "</tv>")
        repository.importEpg(EpgSource("search", "playlist", file.path, SourceKind.FILE))
        val hits = repository.searchProgrammes("Needle", "playlist", now)
        assertEquals(listOf("Needle live", "Needle past"), hits.map { it.second.title })
        assertTrue(repository.searchProgrammes("Needle", "another", now).isEmpty())
    }

    @Test fun unicodeSearchMatchesAcrossPagesWithoutCaseSensitivity() = runBlocking {
        val source = playlist()
        File(source.location).let { it.writeText(it.readText().replace("#EXTINF:-1", "#EXTINF:-1 catchup=\"flussonic\" catchup-days=\"2\"")) }
        repository.importPlaylist(source)
        val now = System.currentTimeMillis() / 1000 * 1000
        val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
        val file = File(directory, "unicode-search.xml")
        file.writeText("<tv><channel id=\"one\"><display-name>Первый</display-name></channel>" +
            (1..620).joinToString("") { i ->
                val title = if (i == 620) "ФУТБОЛ. Матч чемпионов" else "Другая передача"
                "<programme channel=\"one\" start=\"${formatter.format(Instant.ofEpochMilli(now - (i+1) * 60_000))}\" stop=\"${formatter.format(Instant.ofEpochMilli(now - i * 60_000))}\"><title>$title</title></programme>"
            } + "</tv>")
        repository.importEpg(EpgSource("unicode", "playlist", file.path, SourceKind.FILE))
        assertEquals(listOf("ФУТБОЛ. Матч чемпионов"), repository.searchProgrammes("футбол", "playlist", now).map { it.second.title })
        assertEquals(1, repository.searchProgrammes("матч", "playlist", now).size)
    }

    @Test fun updateKeepsPreferencesAndStableIdentity() = runBlocking {
        repository.importPlaylist(playlist())
        val initial = repository.channels("playlist").first().single()
        repository.setFavourite(initial.id, true); repository.setHidden(initial.id, true)
        repository.importPlaylist(playlist("https://example.test/new-token"))
        val updated = repository.channels("playlist", true).first().single()
        assertEquals(initial.id, updated.id); assertTrue(updated.favourite); assertTrue(updated.hidden)
        assertEquals("https://example.test/new-token", updated.url)
    }
    @Test fun categoryIconSurvivesRefreshAndHideAndResets() = runBlocking {
        repository.importPlaylist(playlist())
        repository.setCategoryIcon("playlist", "Новости", "music")
        repository.setCategoryPinned("playlist", "Новости", true)
        repository.setCategoryHidden("playlist", "Новости", true)
        repository.reorderCategories("playlist", listOf("Новости"))
        repository.importPlaylist(playlist("https://example.test/updated"))
        val saved = repository.categories("playlist").first().single()
        assertEquals("music", saved.iconOverride); assertTrue(saved.pinned); assertTrue(saved.hidden)
        repository.setCategoryHidden("playlist", "Новости", false)
        assertEquals("music", repository.categories("playlist").first().single().iconOverride)
        repository.setCategoryIcon("playlist", "Новости", null)
        assertNull(repository.categories("playlist").first().single().iconOverride)
        repository.setCategoryIcon("playlist", "Новости", "unknown")
        assertNull(repository.categories("playlist").first().single().iconOverride)
    }

    @Test fun pinnedCategorySurvivesRefreshAndPreferenceEdits() = runBlocking {
        repository.importPlaylist(playlist())
        repository.setCategoryPinned("playlist", "Новости", true)
        repository.setCategoryHidden("playlist", "Новости", true)
        repository.reorderCategories("playlist", listOf("Новости"))
        repository.importPlaylist(playlist("https://example.test/updated"))
        val category = repository.categories("playlist").first().single()
        assertTrue(category.pinned); assertTrue(category.hidden)
        repository.setCategoryPinned("playlist", "Новости", false)
        val unpinned = repository.categories("playlist").first().single()
        assertFalse(unpinned.pinned); assertTrue(unpinned.hidden)
        assertEquals(category.order, unpinned.order)
    }

    @Test fun listPreferencesAreIndependentAndSurvivePlaylistRefresh() = runBlocking {
        repository.importPlaylist(playlist())
        val id = repository.channels("playlist").first().single().id
        val scope = ChannelListScope("playlist", ChannelListKind.CATEGORY, "Новости")
        val all = ChannelListScope("playlist", ChannelListKind.ALL)
        repository.setChannelPinned(scope, id, true)
        repository.reorderChannelList(scope, listOf(id, "temporarily-missing"))
        assertTrue(repository.channelListPreferences(all).first().isEmpty())
        repository.importPlaylist(playlist("https://example.test/refreshed"))
        val p = repository.channelListPreferences(scope).first().first { it.channelId == id }
        assertTrue(p.pinned); assertEquals(0, p.position)
        assertTrue(repository.channelListPreferences(scope).first().any { it.channelId == "temporarily-missing" })
        repository.setChannelPinned(scope, id, false)
        assertEquals(0, repository.channelListPreferences(scope).first().first { it.channelId == id }.position)
    }

    @Test fun failedPlaylistKeepsOldChannelsAndSanitizedError() = runBlocking {
        val source = playlist(); repository.importPlaylist(source)
        File(source.location).writeText("invalid text with https://private.invalid/secret")
        try { repository.importPlaylist(source); fail("Must reject invalid playlist") } catch (_: java.io.IOException) { }
        assertEquals(1, repository.channels("playlist").first().size)
        val error = repository.playlists.first().single().lastError.orEmpty()
        assertTrue(error.isNotBlank()); assertFalse(error.contains("private"))
    }
    @Test fun malformedEpgDoesNotReplaceActiveGeneration() = runBlocking {
        repository.importPlaylist(playlist()); val source = epg("Рабочая программа"); repository.importEpg(source)
        val channel = repository.channels("playlist").first().single()
        assertEquals("one", channel.epgChannelId)
        File(source.location).writeText("<tv><channel id=\"one\"><display-name>Первый</display-name></channel><programme")
        try { repository.importEpg(source); fail("Must reject malformed XML") } catch (_: java.io.IOException) { }
        val now = System.currentTimeMillis()
        assertEquals("Рабочая программа", repository.programmes(listOf(channel.id), now - 1000, now + 1000).first()[channel.id]?.single()?.title)
    }
    @Test fun epgIconBecomesFallbackForBoundChannel() = runBlocking {
        repository.importPlaylist(playlist())
        val source = epg("Новости")
        val file = File(source.location)
        file.writeText(file.readText().replace("</display-name>", "</display-name><icon src=\"https://example.test/logo.png\"/>"))
        repository.importEpg(source)
        val channel = repository.channels("playlist").first().single()
        assertEquals("https://example.test/logo.png", channel.epgLogo)
        assertEquals("one", channel.epgChannelId)
    }
    @Test fun oldEpgSourceRecoversIconsWithoutReimportingProgrammes() = runBlocking {
        repository.importPlaylist(playlist())
        val source = epg("Сохранённая передача")
        repository.importEpg(source)
        val active = requireNotNull(database.dao().epgSource(source.id))
        database.dao().putEpgSource(active.copy(iconScanDone = false))
        val file = File(source.location)
        file.writeText(file.readText().replace("</display-name>", "</display-name><icon src=\"https://example.test/recovered.png\"/>"))
        repository.updateSettings(AppSettings(playlistRefreshHours = 0, epgRefreshHours = 0))
        repository.refreshStale()
        val channel = repository.channels("playlist").first().single()
        assertEquals("https://example.test/recovered.png", channel.epgLogo)
        assertTrue(requireNotNull(database.dao().epgSource(source.id)).iconScanDone)
        val now = System.currentTimeMillis()
        assertEquals("Сохранённая передача", repository.programmes(listOf(channel.id), now - 1000, now + 1000).first()[channel.id]?.single()?.title)
    }
    @Test fun interruptedEpgKeepsPreviousGeneration() = runBlocking {
        repository.importPlaylist(playlist()); repository.importEpg(epg("Предыдущая"))
        val source = epg("Прерванная")
        val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
        File(source.location).writeText(buildString {
            append("<tv><channel id=\"one\"><display-name>Первый</display-name></channel>")
            repeat(1000) { index ->
                val start = Instant.now().plusSeconds(index * 600L)
                append("<programme channel=\"one\" start=\"${formatter.format(start)}\" stop=\"${formatter.format(start.plusSeconds(600))}\"><title>Прерванная</title></programme>")
            }
            append("</tv>")
        })
        val task = launch { repository.importEpg(source) { if (it.completed >= 500) throw CancellationException("cancel fixture") } }
        task.join()
        val channel = repository.channels("playlist").first().single(); val now = System.currentTimeMillis()
        assertEquals("Предыдущая", repository.programmes(listOf(channel.id), now - 1000, now + 1000).first()[channel.id]?.single()?.title)
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM programmes").use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals(1L, cursor.getLong(0))
        }
    }
    @Test fun nextImportRemovesStagingLeftByKilledProcess() = runBlocking {
        repository.importPlaylist(playlist())
        repository.importEpg(epg("Рабочая"))
        val dao = database.dao()
        dao.putEpgChannels(listOf(EpgChannelEntity("abandoned-source", "abandoned-generation", "one", "Заброшенный")))
        dao.putProgrammes(listOf(ProgrammeEntity("abandoned-source", "abandoned-generation", "abandoned-programme", "one", "Заброшенная", 1, 2, "", "", "")))
        assertEquals(2, tableCount("programmes"))
        repository.importEpg(epg("Новая"))
        assertEquals(1, tableCount("programmes"))
        assertEquals(1, tableCount("epg_channels"))
        val channel = repository.channels("playlist").first().single()
        val now = System.currentTimeMillis()
        assertEquals("Новая", repository.programmes(listOf(channel.id), now - 1000, now + 1000).first()[channel.id]?.single()?.title)
    }
    private fun tableCount(table: String): Int = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        assertTrue(it.moveToFirst()); it.getInt(0)
    }
    @Test fun manualBindingSurvivesAmbiguousSourcesAndRefresh() = runBlocking {
        repository.importPlaylist(playlist())
        val first = epg("Первый источник")
        repository.importEpg(first)
        val secondFile = File(directory, "second.xml").apply { writeText(File(first.location).readText()) }
        val second = first.copy(id = "epg-two", location = secondFile.path)
        repository.importEpg(second)
        val channel = repository.channels("playlist").first().single()
        assertNull(channel.epgSourceId)
        repository.bindEpg(channel.id, second.id, "one")
        repository.refreshEpg(first.id)
        assertEquals(second.id, repository.channels("playlist").first().single().epgSourceId)
    }
    @Test fun removedChannelsRecoverPreferencesWhenTheyReturn() = runBlocking {
        val source = playlist(); repository.importPlaylist(source)
        val original = repository.channels("playlist").first().single()
        repository.setFavourite(original.id, true)
        File(source.location).writeText("#EXTM3U\n#EXTINF:-1 tvg-id=\"two\",Другой\nhttps://example.test/other\n")
        repository.importPlaylist(source)
        assertEquals("Другой", repository.channels("playlist").first().single().name)
        repository.importPlaylist(playlist())
        val restored = repository.channels("playlist").first().single()
        assertEquals(original.id, restored.id); assertTrue(restored.favourite)
    }
    @Test fun changingEpgOffsetAppliesDeltaWithoutNetwork() = runBlocking {
        repository.importPlaylist(playlist()); val source = epg("Программа"); repository.importEpg(source)
        val channel = repository.channels("playlist").first().single(); val now = System.currentTimeMillis()
        val original = repository.programmes(listOf(channel.id), now - 1000, now + 1000).first()[channel.id]!!.single()
        File(source.location).delete()
        repository.updateEpgOffset(source.id, 60); repository.updateEpgOffset(source.id, 120)
        val shifted = repository.programmes(listOf(channel.id), now, now + 10_000_000).first()[channel.id]!!.single()
        assertEquals(original.start + 7_200_000, shifted.start)
        assertEquals(120, repository.epgSources.first().single().offsetMinutes)
    }
    /** Opt-in local fixture benchmark. Credentials remain in device-private files and never log. */
    @Test fun providerLoadBenchmarkWhenLocalFixturesExist(): Unit = runBlocking {
        val importRoot = context.getExternalFilesDir("import")
        val playlistFile = File(importRoot, "provider.m3u")
        val epgFile = File(importRoot, "provider.xml.gz")
        Assume.assumeTrue("Optional private provider fixtures are not present", playlistFile.isFile && epgFile.isFile)
        val databaseName = "provider-benchmark-${UUID.randomUUID()}.db"
        val benchmarkDb = Room.databaseBuilder(context, AirloomDatabase::class.java, databaseName).build()
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "benchmark.preferences_pb") }
        val benchmark = RoomAirloomRepository(context, benchmarkDb, PreferencesStore(store), OkHttpClient())
        try {
            val playlistStart = System.nanoTime()
            val playlistResult = benchmark.importPlaylist(PlaylistSource("provider", "Локальная проверка", playlistFile.path, SourceKind.FILE))
            val playlistMs = (System.nanoTime() - playlistStart) / 1_000_000
            assertTrue("Expected full provider playlist", playlistResult.channels > 2000)
            val epgStart = System.nanoTime()
            val epgResult = benchmark.importEpg(EpgSource("provider-epg", "provider", epgFile.path, SourceKind.FILE))
            val epgMs = (System.nanoTime() - epgStart) / 1_000_000
            assertTrue("Expected full retained provider EPG", epgResult.programmes > 100_000)
            val channels = benchmark.channels("provider").first()
            val matched = channels.filter { it.epgChannelId != null }
            assertTrue("Provider EPG should match channels", matched.size > 100)
            val manualSample = matched.first()
            benchmark.bindEpg(manualSample.id, manualSample.epgSourceId, manualSample.epgChannelId)
            assertTrue(benchmarkDb.dao().allBindings().first { it.channelId == manualSample.id }.manual)
            val visible = matched.take(12).map { it.id }
            val now = System.currentTimeMillis()
            val timings = mutableListOf<Long>()
            var visibleCount = 0
            repeat(10) {
                val start = System.nanoTime()
                val viewport = benchmark.programmes(visible, now - 7_200_000, now + 14_400_000).first()
                timings += (System.nanoTime() - start) / 1_000_000
                assertEquals(visible.toSet(), viewport.keys)
                assertTrue(viewport.values.flatten().all { it.start < now + 14_400_000 && it.end > now - 7_200_000 })
                visibleCount = viewport.values.sumOf { it.size }
            }
            assertTrue("Current viewport should contain programmes", visibleCount > 0)
            timings.sort()
            val heapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576
            android.util.Log.i("AirloomBenchmark", "channels=${playlistResult.channels} programmes=${epgResult.programmes} matched=${matched.size} playlistMs=$playlistMs epgMs=$epgMs viewportProgrammes=$visibleCount viewportMedianMs=${timings[5]} viewportMaxMs=${timings.last()} heapMb=$heapMb")
        } finally {
            benchmarkDb.close()
            context.deleteDatabase(databaseName)
        }
    }
    @Test fun movingEpgSourceRematchesBothPlaylists() = runBlocking {
        val original = playlist()
        repository.importPlaylist(original)
        repository.importPlaylist(original.copy(id = "second-playlist", name = "Второй"))
        val source = epg("Передача")
        repository.importEpg(source)
        assertNotNull(repository.channels("playlist").first().single().epgSourceId)
        repository.importEpg(source.copy(playlistId = "second-playlist"))
        assertNull(repository.channels("playlist").first().single().epgSourceId)
        assertEquals(source.id, repository.channels("second-playlist").first().single().epgSourceId)
    }
    @Test fun onboardingAndAppearanceRoundTrip() = runBlocking {
        val draft = OnboardingState(step = 3, playlistId = "playlist", provider = ProviderProfile(key = "FIXTURE", baseOrigin = "https://iptv.me", epgVariant = EpgVariant.LITE_GZIP))
        repository.updateOnboarding(draft)
        assertEquals(draft, repository.onboarding.first())
        val appearance = AppSettings(theme = ThemeMode.LIGHT, textSize = 2, density = 0, preview = false, playlistRefreshHours = 48, epgRefreshHours = 1, playerControlsSeconds = 10)
        repository.updateSettings(appearance)
        assertEquals(appearance, repository.settings.first())
    }
    @Test fun disabledAutomaticRefreshDoesNotFetchStaleSources() = runBlocking {
        val source = playlist()
        repository.importPlaylist(source)
        val guide = epg("Рабочая")
        repository.importEpg(guide)
        val dao = database.dao()
        dao.putPlaylist(requireNotNull(dao.playlist(source.id)).copy(lastSuccess = 0))
        dao.putEpgSource(requireNotNull(dao.epgSource(guide.id)).copy(lastSuccess = 0))
        repository.updateSettings(AppSettings(playlistRefreshHours = 0, epgRefreshHours = 0))
        File(source.location).delete()
        File(guide.location).delete()
        repository.refreshStale()
        assertNull(repository.playlists.first().single().lastError)
        assertNull(repository.epgSources.first().single().lastError)
        assertEquals(1, repository.channels(source.id).first().size)
        assertEquals(1, dao.epgSource(guide.id)?.programmeCount)
    }
    private fun providerHttp(body: (okhttp3.Request) -> String): RoomAirloomRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(body(request).toResponseBody()).build()
        }.build()
        return RoomAirloomRepository(context, database, preferences, client)
    }

    @Test fun changingProviderIsAtomicWhenNewEpgFails() = runBlocking {
        val oldProfile = ProviderProfile(key = "FIXTURE_OLD", baseOrigin = "https://fixture.test", epgVariant = EpgVariant.FULL_XML)
        val newProfile = oldProfile.copy(key = "FIXTURE_NEW", epgOrigin = "https://epg-new.test")
        val xml = File(epg("Рабочая программа").location).readText()
        repository = providerHttp { request ->
            if (request.url.host == "epg-new.test") "<tv><programme"
            else if (request.url.encodedPath.startsWith("/epg/")) xml
            else "#EXTM3U\n#EXTINF:-1 tvg-id=\"one\" group-title=\"Новости\",Первый\nhttps://example.test/${request.url.encodedPath.substringAfter("/play/").substringBefore('/')}/live.m3u8\n"
        }
        repository.importProviderPlaylist(oldProfile)
        repository.importProviderEpg(oldProfile)
        val oldChannel = repository.channels("iptv-online").first().single()
        repository.setFavourite(oldChannel.id, true)
        val generation = database.dao().epgSource("iptv-online-epg")!!.generation
        try { repository.updateProviderProfile(newProfile); fail("Broken EPG must fail") } catch (_: AirloomFailure) { }
        assertEquals(oldProfile, repository.providerProfile.first())
        assertEquals(oldChannel.url, repository.channels("iptv-online").first().single().url)
        assertTrue(repository.channels("iptv-online").first().single().favourite)
        assertEquals(generation, database.dao().epgSource("iptv-online-epg")!!.generation)
        assertEquals(1, tableCount("programmes"))
    }

    @Test fun queuedRefreshReadsProfileAfterAcquiringImportGate() = runBlocking {
        val old = ProviderProfile(key = "FIXTURE_OLD", baseOrigin = "https://fixture.test")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        var blocked = false
        repository = providerHttp { request ->
            val key = request.url.encodedPath.substringAfter("/play/").substringBefore('/')
            requests += key
            if (blocked && key == old.key) { entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            "#EXTM3U\n#EXTINF:-1 tvg-id=\"one\",Первый\nhttps://example.test/$key/live.m3u8\n"
        }
        repository.importProviderPlaylist(old)
        requests.clear()
        blocked = true
        val first = async { repository.refreshPlaylist("iptv-online") }
        withContext(Dispatchers.IO) { check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
        val update = async(start = CoroutineStart.UNDISPATCHED) { repository.updateProviderProfile(old.copy(key = "FIXTURE_NEW")) }
        val queued = async(start = CoroutineStart.UNDISPATCHED) { repository.refreshPlaylist("iptv-online") }
        release.countDown()
        first.await(); update.await(); queued.await()
        assertEquals(listOf("FIXTURE_OLD", "FIXTURE_NEW", "FIXTURE_NEW"), requests)
        assertEquals("FIXTURE_NEW", repository.providerProfile.first()?.key)
    }

    @Test fun cancellationAtCommitReportsSavedAndKeepsPublishedGeneration() = runBlocking {
        repository.importPlaylist(playlist())
        val source = epg("Сохранённая")
        var committed = false
        val task = launch {
            val job = currentCoroutineContext()
            repository.importEpg(source) { progress ->
                if (progress.committed) { committed = true; job.cancel() }
            }
        }
        task.join()
        assertTrue(committed)
        val channel = repository.channels("playlist").first().single()
        assertEquals("Сохранённая", repository.programmes(listOf(channel.id), System.currentTimeMillis() - 1000, System.currentTimeMillis() + 1000).first()[channel.id]?.single()?.title)
    }

    @Test fun searchAppliesVisibilityAndArchiveEligibilityBeforeLimit() = runBlocking {
        val dao = database.dao()
        val now = System.currentTimeMillis()
        val hidden = Channel("hidden", "p", "Hidden", "https://example.test/hidden", group = "Hidden", catchup = CatchupSpec("shift", days = 7))
        val unavailable = hidden.copy(id = "unavailable", name = "Unavailable", group = "Visible", catchup = CatchupSpec())
        val available = hidden.copy(id = "available", name = "Available", group = "Visible")
        dao.putPlaylist(PlaylistSource("p", "Fixture", "fixture", SourceKind.FILE).entity())
        dao.putChannels(listOf(hidden, unavailable, available).map { it.entity() })
        dao.putEpgSource(EpgSource("e", "p", "fixture", SourceKind.FILE).entity("g"))
        dao.putEpgChannels(listOf(hidden, unavailable, available).map { EpgChannelEntity("e", "g", it.id, it.name) })
        dao.putBindings(listOf(hidden, unavailable, available).map { BindingEntity(it.id, "e", it.id, true) })
        repository.setCategoryHidden("p", "Hidden", true)
        withContext(Dispatchers.IO) {
            dao.putProgrammes((0 until 120).flatMap { i -> listOf(
                ProgrammeEntity("e", "g", "h$i", hidden.id, "Матч", now - 100_000 - i, now - 10_000, "", "", ""),
                ProgrammeEntity("e", "g", "u$i", unavailable.id, "Матч", now - 200_000 - i, now - 10_000, "", "", ""))
            } + ProgrammeEntity("e", "g", "valid", available.id, "МАТЧ", now - 300_000, now - 10_000, "Полное описание", "", ""))
        }
        val results = repository.searchProgrammes("матч", "p", now)
        assertEquals(listOf("available"), results.map { it.first.id })
        assertEquals("Полное описание", results.single().second.description)
    }

    @Test fun independentSettingsChangesAreAtomicAndSelectionRoundTrips() = runBlocking {
        coroutineScope {
            launch { repository.changeSettings { it.copy(accent = Accent.TEAL) } }
            launch { repository.changeSettings { it.copy(textSize = 2) } }
        }
        assertEquals(Accent.TEAL, repository.settings.first().accent)
        assertEquals(2, repository.settings.first().textSize)
        repository.changeSettings { it.copy(lastCategory = "Избранное", channelSelection = ChannelSelection.Category("p", "Избранное")) }
        assertEquals(ChannelSelection.Category("p", "Избранное"), repository.settings.first().channelSelection)
    }

}
