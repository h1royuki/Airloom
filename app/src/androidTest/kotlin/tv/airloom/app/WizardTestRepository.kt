package tv.airloom.app

import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import tv.airloom.core.*

/** Private in-memory state for UI tests: never reads or mutates the user's Room/DataStore. */
internal class WizardTestRepository : AirloomRepository {
    var failProviderSave = false
    var failListMutation = false
    override suspend fun applyListMutation(mutation: ListMutation) {
        if (failListMutation) throw IOException("Synthetic list write failure")
        when (mutation) {
            is ListMutation.Channels -> importedChannels.value = importedChannels.value.map {
                if (it.id in mutation.ids) it.copy(hidden = mutation.hidden ?: it.hidden, favourite = mutation.favourite ?: it.favourite) else it
            }
            is ListMutation.Categories -> mutation.names.forEach { name ->
                mutation.hidden?.let { setCategoryHidden(mutation.playlistId, name, it) }
                mutation.pinned?.let { setCategoryPinned(mutation.playlistId, name, it) }
            }
            is ListMutation.Pin -> setChannelPinned(mutation.scope, mutation.id, mutation.pinned)
            is ListMutation.ChannelOrder -> reorderChannelList(mutation.scope, mutation.ids)
            is ListMutation.CategoryOrder -> reorderCategories(mutation.playlistId, mutation.names)
            is ListMutation.Icon -> setCategoryIcon(mutation.playlistId, mutation.name, mutation.icon)
            is ListMutation.Binding -> bindEpg(mutation.id, mutation.epg?.sourceId, mutation.epg?.id)
        }
    }
    override val providerProfile = MutableStateFlow<ProviderProfile?>(null)
    override val playlists = MutableStateFlow<List<PlaylistSource>>(emptyList())
    override val epgSources = MutableStateFlow<List<EpgSource>>(emptyList())
    override val settings = MutableStateFlow(AppSettings())
    override val onboarding = MutableStateFlow(OnboardingState())
    val testProgrammes = MutableStateFlow<Map<String, List<Programme>>>(emptyMap())
    var failReorder = false
    var failNextSettings = false
    var failNextOnboarding = false
    var searchCalls = 0
    var searchCancellations = 0
    var searchGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private val categoryPreferences = MutableStateFlow<Map<Pair<String, String>, Category>>(emptyMap())
    private val importedChannels = MutableStateFlow<List<Channel>>(emptyList())
    var importAttempts = 0
        private set
    var importOverride: (suspend (PlaylistSource, (ImportProgress) -> Unit) -> ImportResult)? = null

    fun seedCompletedGuide(channel: Channel, startup: StartupMode) {
        providerProfile.value = ProviderProfile(key = "FIXTURE")
        playlists.value = listOf(PlaylistSource(channel.playlistId, "Тестовый источник", "fixture.m3u", SourceKind.FILE, channelCount = 1))
        importedChannels.value = listOf(channel)
        settings.value = AppSettings(startup = startup, selectedPlaylistId = channel.playlistId, lastChannelId = channel.id)
        onboarding.value = OnboardingState(complete = true, step = 6, playlistId = channel.playlistId)
    }

    fun replaceChannels(channels: List<Channel>) { importedChannels.value = channels }

    override fun channels(playlistId: String?, includeHidden: Boolean) = importedChannels.map { list ->
        list.filter { (playlistId == null || it.playlistId == playlistId) && (includeHidden || !it.hidden) }
    }
    private val listPreferences = mutableMapOf<ChannelListScope, MutableStateFlow<List<ChannelListPreference>>>()
    override fun channelListPreferences(scope: ChannelListScope) = listPreferences.getOrPut(scope) { MutableStateFlow(emptyList()) }
    override suspend fun setChannelPinned(scope: ChannelListScope, channelId: String, value: Boolean) {
        val flow = channelListPreferences(scope)
        val old = flow.value.firstOrNull { it.channelId == channelId } ?: ChannelListPreference(channelId)
        flow.value = flow.value.filterNot { it.channelId == channelId } + old.copy(pinned = value)
    }
    override suspend fun reorderChannelList(scope: ChannelListScope, ids: List<String>) {
        if (failReorder) throw IOException("Synthetic save error")
        val flow = channelListPreferences(scope)
        flow.value = flow.value.filterNot { it.channelId in ids } + ids.mapIndexed { i, id -> (flow.value.firstOrNull { it.channelId == id } ?: ChannelListPreference(id)).copy(position = i) }
    }
    override fun categories(playlistId: String?) = combine(channels(playlistId, true), categoryPreferences) { list, prefs ->
        list.groupBy { it.playlistId to it.group }.map { (key, value) ->
            val p = prefs[key]
            Category(key.first, key.second, value.size, p?.hidden ?: false, p?.order ?: value.minOf { it.number }, p?.pinned ?: false, p?.iconOverride)
        }.sortedBy { it.order }
    }
    override fun programmes(channelIds: List<String>, start: Long, end: Long): Flow<Map<String, List<Programme>>> { return testProgrammes.map { list -> list.filterKeys { it in channelIds }.mapValues { (_, programmes) -> programmes.filter { it.start < end && it.end > start } } } }
    override suspend fun importPlaylist(source: PlaylistSource, progress: (ImportProgress) -> Unit): ImportResult {
        importAttempts++
        progress(ImportProgress("Проверка плейлиста"))
        importOverride?.let { return it(source, progress) }
        // Network failure is injected deterministically; this suite tests error UI and retry,
        // while data-module tests own source/network validation.
        if (source.kind != SourceKind.FILE) throw IOException("Не удалось загрузить плейлист. Проверьте адрес.")
        val parsed = File(source.location).bufferedReader().use { M3uParser.parse(it, source.id) }
        importedChannels.value = importedChannels.value.filterNot { it.playlistId == source.id } + parsed.channels
        playlists.value = playlists.value.filterNot { it.id == source.id } + source.copy(channelCount = parsed.channels.size)
        return ImportResult(source.id, parsed.channels.size, parsed.channels.map { it.group }.distinct().size,
            parsed.channels.count { it.catchup.days > 0 }, parsed.epgUrls, warnings = parsed.warnings)
    }
    override suspend fun importProviderPlaylist(profile: ProviderProfile, progress: (ImportProgress) -> Unit): ImportResult {
        importAttempts++
        progress(ImportProgress("Проверка плейлиста"))
        val source = PlaylistSource("iptv-online", "IPTV.ONLINE", ProviderEndpoints.playlist(ProviderEndpoints.normalized(profile)))
        val result = importOverride?.invoke(source, progress) ?: run {
            val channel = Channel("provider-channel", source.id, "Тестовый канал", "https://example.invalid/never-request-this-stream.m3u8", group = "Тест")
            importedChannels.value = listOf(channel)
            ImportResult(source.id, channels = 1, categories = 1)
        }
        playlists.value = listOf(source.copy(channelCount = result.channels))
        providerProfile.value = ProviderEndpoints.normalized(profile)
        return result
    }
    override suspend fun importProviderEpg(profile: ProviderProfile, progress: (ImportProgress) -> Unit): ImportResult {
        progress(ImportProgress("Обработка телепрограммы"))
        val result = ImportResult("iptv-online-epg", programmes = 3, matchedChannels = importedChannels.value.size)
        epgSources.value = listOf(EpgSource(result.sourceId, "iptv-online", ProviderEndpoints.epg(profile), programmeCount = 3))
        providerProfile.value = profile
        return result
    }
    override suspend fun updateProviderProfile(profile: ProviderProfile, progress: (ImportProgress) -> Unit) { if (failProviderSave) throw IOException("Synthetic provider failure"); providerProfile.value = profile }
    override suspend fun checkProviderConnection(profile: ProviderProfile) = Unit
    override suspend fun updateSettings(value: AppSettings) {
        if (failNextSettings) { failNextSettings = false; throw IOException("synthetic-private-value") }
        settings.value = value
    }
    override suspend fun updateOnboarding(value: OnboardingState) {
        if (failNextOnboarding) { failNextOnboarding = false; throw IOException("synthetic-private-value") }
        onboarding.value = value
    }
    override suspend fun refreshStale() = Unit
    override suspend fun searchProgrammes(query: String, playlistId: String?, now: Long): List<Pair<Channel, Programme>> {
        searchCalls++
        try { searchGate?.await() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { searchCancellations++; throw cancelled }
        return importedChannels.value.filter { playlistId == null || it.playlistId == playlistId }.flatMap { channel -> testProgrammes.value[channel.id].orEmpty().filter { it.title.contains(query, true) && it.start <= now }.map { channel to it } } }
    override suspend fun findEpgChannels(query: String): List<EpgChannel> = emptyList()
    override suspend fun importEpg(source: EpgSource, progress: (ImportProgress) -> Unit): ImportResult {
        require(source.kind == SourceKind.FILE) { "UI tests only accept local synthetic EPG" }
        progress(ImportProgress("Обработка телепрограммы"))
        val epgIds = mutableSetOf<String>()
        val result = File(source.location).inputStream().use { input ->
            XmltvParser.parse(input, source.id, source.offsetMinutes, onChannel = { epgIds += it.id }, onProgramme = {})
        }
        epgSources.value = epgSources.value.filterNot { it.id == source.id } + source.copy(programmeCount = result.programmes)
        val matched = importedChannels.value.count { it.playlistId == source.playlistId && it.tvgId in epgIds }
        importedChannels.value = importedChannels.value.map { channel ->
            if (channel.playlistId == source.playlistId && channel.tvgId in epgIds)
                channel.copy(epgSourceId = source.id, epgChannelId = channel.tvgId)
            else channel
        }
        return ImportResult(source.id, programmes = result.programmes, matchedChannels = matched, warnings = result.skipped)
    }
    override suspend fun refreshPlaylist(id: String, progress: (ImportProgress) -> Unit): ImportResult = error("Unexpected refresh")
    override suspend fun refreshEpg(id: String, progress: (ImportProgress) -> Unit): ImportResult = error("Unexpected refresh")
    override suspend fun setFavourite(id: String, value: Boolean) = error("Unexpected edit")
    override suspend fun setHidden(id: String, value: Boolean) { importedChannels.value = importedChannels.value.map { if (it.id == id) it.copy(hidden = value) else it } }
    override suspend fun reorderChannels(ids: List<String>, favourites: Boolean) = error("Unexpected edit")
    override suspend fun setCategoryHidden(playlistId: String, name: String, value: Boolean) { val key = playlistId to name; categoryPreferences.value = categoryPreferences.value + (key to (categoryPreferences.value[key] ?: Category(playlistId, name, 0)).copy(hidden = value)) }
    override suspend fun setCategoryIcon(playlistId: String, name: String, icon: String?) { val key = playlistId to name; categoryPreferences.value = categoryPreferences.value + (key to (categoryPreferences.value[key] ?: Category(playlistId, name, 0)).copy(iconOverride = icon)) }
    override suspend fun setCategoryPinned(playlistId: String, name: String, value: Boolean) { val key = playlistId to name; categoryPreferences.value = categoryPreferences.value + (key to (categoryPreferences.value[key] ?: Category(playlistId, name, 0)).copy(pinned = value)) }
    override suspend fun reorderCategories(playlistId: String, names: List<String>) {
        if (failReorder) throw IOException("Synthetic save error")
        categoryPreferences.value = categoryPreferences.value + names.mapIndexed { i, name -> val key = playlistId to name; key to (categoryPreferences.value[key] ?: Category(playlistId, name, 0)).copy(order = i) }
    }
    override suspend fun bindEpg(channelId: String, sourceId: String?, epgChannelId: String?) = error("Unexpected edit")
    override suspend fun deletePlaylist(id: String) = error("Unexpected edit")
    override suspend fun deleteEpgSource(id: String) = error("Unexpected edit")
    override suspend fun updateEpgOffset(id: String, minutes: Int) = error("Unexpected edit")
}
