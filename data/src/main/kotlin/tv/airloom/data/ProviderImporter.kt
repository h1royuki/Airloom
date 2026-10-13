package tv.airloom.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import tv.airloom.core.*
import java.io.*
import java.net.URI
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Prepares provider data outside transactions and publishes one coherent catalogue. */
internal class ProviderImporter(
    private val context: Context,
    private val database: AirloomDatabase,
    private val http: OkHttpClient,
    private val runtime: AirloomRuntime,
    coordinator: ImportCoordinator,
) {
    private val dao = database.dao()
    private val imports = coordinator.mutex
    private data class PreparedPlaylist(val source: PlaylistSource, val channels: List<Channel>, val parsed: ParsedPlaylist)
    private data class PreparedEpg(val source: EpgSource, val generation: String, val count: Int, val warnings: Int, val previousPlaylist: String?)

    private suspend fun preparePlaylist(source: PlaylistSource, progress: (ImportProgress) -> Unit): PreparedPlaylist {
        progress(ImportProgress("Загрузка плейлиста"))
        val parsed = open(source.location, source.kind, source.userAgent, source.referer).use {
            M3uParser.parse(it.bufferedReader(), source.id)
        }
        currentCoroutineContext().ensureActive()
        val previous = dao.allChannels(source.id).map { it.model() }
        val channels = reconcileChannels(parsed.channels, previous).map {
            it.copy(userAgent = it.userAgent.ifBlank { source.userAgent }, referer = it.referer.ifBlank { source.referer })
        }
        return PreparedPlaylist(source, channels, parsed)
    }

    private suspend fun publishPlaylist(value: PreparedPlaylist) {
        val (source, channels) = value
        dao.putPlaylist(source.copy(lastSuccess = runtime.now(), lastError = null, channelCount = channels.size).entity())
        dao.deactivate(source.id)
        dao.putChannels(channels.map { it.entity() })
        dao.initializePreferences(channels.map { PreferenceEntity(it.id, position = it.number, favouritePosition = it.number) })
        dao.initializeCategories(channels.groupBy { it.group }.map { (name, items) ->
            CategoryPreferenceEntity(source.id, name, position = items.minOf { it.number })
        })
    }

    private fun PreparedPlaylist.result() = ImportResult(source.id, channels.size,
        channels.map { it.group }.distinct().size,
        channels.count { it.catchup.days > 0 && it.catchup.mode.isNotBlank() }, parsed.epgUrls, warnings = parsed.warnings)

    private suspend fun prepareEpg(source: EpgSource, playlist: PlaylistSource, history: Int,
                                   progress: (ImportProgress) -> Unit): PreparedEpg {
        val generation = UUID.randomUUID().toString()
        try {
            dao.discardAbandonedProgrammes()
            dao.discardAbandonedEpgChannels()
            val previous = dao.epgSource(source.id)
            val now = runtime.now()
            // Without any archive the schedule still needs the earlier part of today, and programme search looks
            // back at least two days, so the window never starts later than that.
            val earliest = now - history.coerceIn(MIN_HISTORY_DAYS, 365) * DAY
            val latest = now + 7 * DAY
            val channels = ArrayList<EpgChannelEntity>(256)
            val programmes = ArrayList<ProgrammeEntity>(EPG_BATCH)
            var count = 0
            var channelCount = 0
            val job = currentCoroutineContext()
            progress(ImportProgress("Загрузка телепрограммы"))
            val parsed = open(source.location, source.kind, playlist.userAgent, playlist.referer).use { input ->
                XmltvParser.parse(input, source.id, source.offsetMinutes, onChannel = { c ->
                    job.ensureActive()
                    channels += EpgChannelEntity(source.id, generation, c.id, c.name, resolveEpgIcon(c.icon, source.location))
                    channelCount++
                    if (channels.size >= 256) { dao.putEpgChannels(channels); channels.clear() }
                }, accept = { start, end -> job.ensureActive(); end > earliest && start < latest }, onProgramme = { programme ->
                    job.ensureActive()
                    programmes += programme.entity(generation)
                    count++
                    if (programmes.size >= EPG_BATCH) {
                        dao.putProgrammes(programmes)
                        programmes.clear()
                        progress(ImportProgress("Обработка телепрограммы", count))
                    }
                })
            }
            job.ensureActive()
            require(channelCount > 0)
            if (channels.isNotEmpty()) dao.putEpgChannels(channels)
            if (programmes.isNotEmpty()) dao.putProgrammes(programmes)
            progress(ImportProgress("Телепрограмма подготовлена", count, count))
            return PreparedEpg(source.copy(lastSuccess = now, lastError = null, programmeCount = count),
                generation, count, parsed.skipped, previous?.playlistId)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                dao.discardProgrammes(source.id, generation)
                dao.discardEpgChannels(source.id, generation)
            }
            throw error
        }
    }

    private suspend fun publishEpg(value: PreparedEpg) {
        dao.putEpgSource(value.source.entity(value.generation))
        value.previousPlaylist?.takeIf { it != value.source.playlistId }?.let { rematch(it) }
    }

    /** Cancellation is honoured before publication. Once publication begins it completes atomically. */
    private suspend fun publish(profile: ProviderProfile?, playlist: PreparedPlaylist?, epg: PreparedEpg?,
                                progress: (ImportProgress) -> Unit) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            database.withTransaction {
                playlist?.let { publishPlaylist(it) }
                epg?.let { publishEpg(it) }
                profile?.let { dao.putProviderProfile(it.entity()) }
                (playlist?.source?.id ?: epg?.source?.playlistId)?.let { rematch(it) }
            }
            progress(ImportProgress("Изменения сохранены", committed = true))
            epg?.let {
                // Cleanup is best-effort after commit; the next import also removes abandoned rows.
                runCatching { dao.pruneProgrammes(it.source.id, it.generation); dao.pruneEpgChannels(it.source.id, it.generation) }
            }
        }
    }

    private suspend fun <T> importing(epg: Boolean, sourceId: String,
                                      action: suspend () -> T): T = withContext(runtime.io) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            ensureActive()
            val failure = importFailure(error, epg)
            if (epg) dao.epgError(sourceId, failure.kind.userMessage())
            else dao.playlistError(sourceId, failure.kind.userMessage())
            throw failure
        }
    }

    suspend fun importPlaylist(source: PlaylistSource, progress: (ImportProgress) -> Unit) = imports.withLock {
        importing(false, source.id) {
            val value = preparePlaylist(source, progress)
            publish(null, value, null, progress)
            value.result()
        }
    }

    suspend fun importEpg(source: EpgSource, progress: (ImportProgress) -> Unit) = imports.withLock {
        importEpgLocked(source, null, progress)
    }

    private suspend fun importEpgLocked(source: EpgSource, profile: ProviderProfile?, progress: (ImportProgress) -> Unit) =
        importing(true, source.id) {
            val playlist = requireNotNull(dao.playlist(source.playlistId)).model()
            val history = dao.allChannels(source.playlistId).filter { it.active }.maxOfOrNull { it.catchupDays } ?: 0
            val value = prepareEpg(source, playlist, history, progress)
            try {
                publish(profile, null, value, progress)
                ImportResult(source.id, programmes = value.count, matchedChannels = dao.allBindings().count { it.sourceId == source.id }, warnings = value.warnings)
            } finally { discardUnpublished(value) }
        }

    private suspend fun discardUnpublished(value: PreparedEpg?) = withContext(NonCancellable + runtime.io) {
        if (value != null && dao.epgSource(value.source.id)?.generation != value.generation) {
            dao.discardProgrammes(value.source.id, value.generation)
            dao.discardEpgChannels(value.source.id, value.generation)
        }
    }

    private suspend fun providerPlaylist(profile: ProviderProfile): PlaylistSource {
        val previous = dao.playlist(PROVIDER_PLAYLIST_ID)
        return PlaylistSource(PROVIDER_PLAYLIST_ID, "IPTV.ONLINE", ProviderEndpoints.playlist(profile),
            userAgent = previous?.userAgent ?: "Airloom/0.1", referer = previous?.referer.orEmpty())
    }

    private suspend fun providerEpg(profile: ProviderProfile): EpgSource =
        dao.epgSource(PROVIDER_EPG_ID)?.model()?.copy(location = ProviderEndpoints.epg(profile))
            ?: EpgSource(PROVIDER_EPG_ID, PROVIDER_PLAYLIST_ID, ProviderEndpoints.epg(profile))

    suspend fun importProviderPlaylist(profile: ProviderProfile, progress: (ImportProgress) -> Unit) = imports.withLock {
        importProviderPlaylistLocked(ProviderEndpoints.normalized(profile), progress)
    }

    private suspend fun importProviderPlaylistLocked(profile: ProviderProfile, progress: (ImportProgress) -> Unit) =
        importing(false, PROVIDER_PLAYLIST_ID) {
            val value = preparePlaylist(providerPlaylist(profile), progress)
            publish(profile, value, null, progress)
            value.result()
        }

    suspend fun importProviderEpg(profile: ProviderProfile, progress: (ImportProgress) -> Unit) = imports.withLock {
        requireNotNull(dao.currentProviderProfile())
        val normalized = ProviderEndpoints.normalized(profile)
        importEpgLocked(providerEpg(normalized), normalized, progress)
    }

    suspend fun updateProviderProfile(profile: ProviderProfile, progress: (ImportProgress) -> Unit) = imports.withLock {
        importing(false, PROVIDER_PLAYLIST_ID) {
            val normalized = ProviderEndpoints.normalized(profile)
            val previous = dao.currentProviderProfile()?.model()
            val playlist = if (previous == null || ProviderEndpoints.playlist(previous) != ProviderEndpoints.playlist(normalized))
                preparePlaylist(providerPlaylist(normalized), progress) else null
            var epg: PreparedEpg? = null
            try {
                if (dao.epgSource(PROVIDER_EPG_ID) != null &&
                    (previous == null || ProviderEndpoints.epg(previous) != ProviderEndpoints.epg(normalized))) {
                    val source = playlist?.source ?: requireNotNull(dao.playlist(PROVIDER_PLAYLIST_ID)).model()
                    val history = playlist?.channels?.maxOfOrNull { it.catchup.days }
                        ?: dao.allChannels(source.id).filter { it.active }.maxOfOrNull { it.catchupDays } ?: 0
                    epg = prepareEpg(providerEpg(normalized), source, history, progress)
                }
                publish(normalized, playlist, epg, progress)
            } finally { discardUnpublished(epg) }
        }
    }
    suspend fun checkProviderConnection(profile: ProviderProfile) = withContext(runtime.io) {
        val normalized = ProviderEndpoints.normalized(profile)
        open(ProviderEndpoints.playlist(normalized), SourceKind.URL, "Airloom/0.1", "").use { input ->
            val prefix = ByteArray(512)
            var count = 0
            while (count < prefix.size) {
                val read = input.read(prefix, count, prefix.size - count)
                if (read < 0) break
                count += read
                if (String(prefix, 0, count, Charsets.UTF_8).trimStart('\uFEFF', '\n', '\r', ' ', '\t').length >= 7) break
            }
            require(count > 0 && String(prefix, 0, count, Charsets.UTF_8).trimStart('\uFEFF', '\n', '\r', ' ', '\t').startsWith("#EXTM3U")) {
                "Сервер ответил, но не вернул плейлист IPTV.ONLINE."
            }
        }
    }
    suspend fun rematch(playlistId: String) {
        val candidates = dao.epgChannels(playlistId).map { EpgChannel(it.sourceId, it.id, it.name) }
        val matcher = EpgMatcher(candidates)
        val existing = dao.allBindings().associateBy { it.channelId }
        val newBindings = dao.allChannels(playlistId).filter { it.active }.mapNotNull { channel ->
            val previous = existing[channel.id]
            if (previous?.manual == true) previous else {
                val match = matcher.binding(channel.model())
                if (match == null) { dao.deleteBinding(channel.id); null } else BindingEntity(match.channelId, match.sourceId, match.epgChannelId, false)
            }
        }
        dao.putBindings(newBindings)
    }
    suspend fun refreshPlaylist(id: String, progress: (ImportProgress) -> Unit) = imports.withLock { refreshPlaylistLocked(id, progress) }
    private suspend fun refreshPlaylistLocked(id: String, progress: (ImportProgress) -> Unit): ImportResult {
        return if (id == PROVIDER_PLAYLIST_ID) importProviderPlaylistLocked(requireNotNull(dao.currentProviderProfile()).model(), progress)
        else importing(false, id) {
            val value = preparePlaylist(requireNotNull(dao.playlist(id)).model(), progress)
            publish(null, value, null, progress)
            value.result()
        }
    }

    suspend fun refreshEpg(id: String, progress: (ImportProgress) -> Unit) = imports.withLock { refreshEpgLocked(id, progress) }
    private suspend fun refreshEpgLocked(id: String, progress: (ImportProgress) -> Unit): ImportResult {
        return if (id == PROVIDER_EPG_ID) {
            val profile = requireNotNull(dao.currentProviderProfile()).model()
            importEpgLocked(providerEpg(profile), null, progress)
        } else importEpgLocked(requireNotNull(dao.epgSource(id)).model(), null, progress)
    }

    /** Scheduled work rechecks its interval and success timestamp after waiting for another import. */
    suspend fun refreshDue(id: String, kind: RefreshKind, hours: suspend () -> Int) = imports.withLock {
        val interval = hours()
        val success = if (kind == RefreshKind.PLAYLIST) dao.playlist(id)?.lastSuccess else dao.epgSource(id)?.lastSuccess
        if (interval > 0 && success != null && runtime.now() - success >= interval * HOUR) {
            if (kind == RefreshKind.PLAYLIST) refreshPlaylistLocked(id) {} else refreshEpgLocked(id) {}
        }
    }
    suspend fun refreshMissingEpgIcons() = imports.withLock {
        withContext(runtime.io) {
            dao.epgSourcesNeedingIconScan().forEach { source ->
                try {
                    val playlist = dao.playlist(source.playlistId) ?: return@forEach
                    val icons = ArrayList<Pair<String, String>>()
                    open(source.location, SourceKind.valueOf(source.kind), playlist.userAgent, playlist.referer).use { input ->
                        XmltvParser.parseChannels(input, source.id) { channel ->
                            resolveEpgIcon(channel.icon, source.location).takeIf(String::isNotBlank)?.let { icons += channel.id to it }
                        }
                    }
                    database.withTransaction {
                        icons.forEach { (id, icon) -> dao.setEpgChannelIcon(source.id, source.generation, id, icon) }
                        dao.markEpgIconsScanned(source.id, source.generation)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* A later refresh can still populate the icons. */ }
            }
        }
    }
    private fun resolveEpgIcon(raw: String, sourceLocation: String): String = runCatching {
        val value = raw.trim().replace(" ", "%20")
        if (value.isEmpty()) return@runCatching ""
        val base = URI(sourceLocation)
        val icon = URI(value)
        val resolved = if (icon.isAbsolute) icon else if (base.scheme in listOf("http", "https")) base.resolve(icon) else null
        resolved?.takeIf { it.scheme in listOf("http", "https") }?.toString().orEmpty()
    }.getOrDefault("")
    private suspend fun open(location: String, kind: SourceKind, userAgent: String, referer: String): InputStream {
        require(location.isNotBlank()) { "empty" }
        val job = currentCoroutineContext()
        val input = if (kind == SourceKind.URL) {
            val request = Request.Builder().url(location).header("User-Agent", userAgent.ifBlank { "Airloom/0.1" }).apply { if (referer.isNotBlank()) header("Referer", referer) }.build()
            val call = http.newCall(request)
            val response = suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) { if (continuation.isActive) continuation.resume(response) { _, value, _ -> value.close() } else response.close() }
                })
            }
            if (!response.isSuccessful) { val code = response.code; response.close(); throw HttpImportException(code) }
            val body = response.body ?: run { response.close(); throw IOException() }
            val cancellation = CoroutineScope(job).launch(runtime.io, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            object : FilterInputStream(body.byteStream()) { override fun close() { cancellation.cancel(); response.close() } }
        } else {
            val uri = Uri.parse(location)
            if (uri.scheme == "content") context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
            else {
                val file = File(if (uri.scheme == "file") requireNotNull(uri.path) else location).canonicalFile
                val roots = listOfNotNull(context.getExternalFilesDir("import"), File(context.filesDir, "import")).map { it.canonicalFile }
                require(roots.any { file.path.startsWith(it.path + File.separator) }) { "folder" }
                FileInputStream(file)
            }
        }
        return object : FilterInputStream(input) {
            override fun read(): Int { job.ensureActive(); return super.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int { job.ensureActive(); return super.read(b, off, len) }
        }.buffered(64 * 1024)
    }
    private fun importFailure(error: Exception, epg: Boolean): AirloomFailure = when (error) {
        is AirloomFailure -> error
        is HttpImportException -> AirloomFailure(when (error.code) {
            401, 403 -> FailureKind.ACCESS
            404 -> FailureKind.NOT_FOUND
            else -> FailureKind.NETWORK
        })
        is java.net.UnknownHostException, is java.net.SocketTimeoutException -> AirloomFailure(FailureKind.NETWORK)
        is SecurityException -> AirloomFailure(FailureKind.ACCESS)
        is FileNotFoundException -> AirloomFailure(FailureKind.NOT_FOUND)
        else -> AirloomFailure(if (epg) FailureKind.EPG else FailureKind.PLAYLIST)
    }
    private class HttpImportException(val code: Int) : IOException()
    private companion object { const val HOUR = 3_600_000L; const val DAY = 24 * HOUR; const val MIN_HISTORY_DAYS = 2; const val EPG_BATCH = 6_000; const val PROVIDER_PLAYLIST_ID = "iptv-online"; const val PROVIDER_EPG_ID = "iptv-online-epg" }
}
