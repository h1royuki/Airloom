package tv.airloom.data

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import tv.airloom.core.*
import java.io.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomAirloomRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AirloomDatabase,
    private val preferences: PreferencesStore,
    private val http: OkHttpClient,
    private val runtime: AirloomRuntime = AirloomRuntime(),
    private val coordinator: ImportCoordinator = ImportCoordinator(),
) : AirloomRepository {
    private val dao = database.dao()
    private val imports = coordinator.mutex
    override val providerProfile = dao.providerProfile().map { it?.model() }
    override val playlists = dao.playlists().map { list -> list.map { it.model() } }
    override val epgSources = dao.epgSources().map { list -> list.map { it.model() } }
    override val settings = preferences.settings
    override val onboarding = preferences.onboarding
    private val catalogue = CatalogueReader(dao, runtime)
    private val importer = ProviderImporter(context, database, http, runtime, coordinator)
    override fun channels(playlistId: String?, includeHidden: Boolean) = catalogue.channels(playlistId, includeHidden)
    override fun channelListPreferences(scope: ChannelListScope) = dao.listPreferences(scope.playlistId, scope.kind.name, scope.category)
        .map { rows -> rows.map { ChannelListPreference(it.channelId, it.pinned, it.position) } }
    override suspend fun setChannelPinned(scope: ChannelListScope, channelId: String, value: Boolean) { database.withTransaction {
        val old = dao.listPreference(scope.playlistId, scope.kind.name, scope.category, channelId)
            ?: ChannelListPreferenceEntity(scope.playlistId, scope.kind.name, scope.category, channelId)
        dao.putListPreference(old.copy(pinned = value))
    } }
    override suspend fun reorderChannelList(scope: ChannelListScope, ids: List<String>) { database.withTransaction {
        val existing = dao.currentListPreferences(scope.playlistId, scope.kind.name, scope.category).associateBy { it.channelId }
        dao.putListPreferences(ids.distinct().mapIndexed { index, id ->
            (existing[id] ?: ChannelListPreferenceEntity(scope.playlistId, scope.kind.name, scope.category, id)).copy(position = index)
        })
    } }
    override suspend fun applyListMutation(mutation: ListMutation) { database.withTransaction {
        when (mutation) {
            is ListMutation.Channels -> {
                val active = dao.activeChannels().map { it.id }.toSet()
                require(active.containsAll(mutation.ids)) { "Catalogue changed" }
                mutation.ids.forEach { id ->
                    val old = dao.preference(id) ?: PreferenceEntity(id)
                    dao.putPreference(old.copy(hidden = mutation.hidden ?: old.hidden, favourite = mutation.favourite ?: old.favourite))
                }
            }
            is ListMutation.Categories -> {
                val active = dao.activeChannels().filter { it.playlistId == mutation.playlistId }.map { it.category }.toSet()
                require(active.containsAll(mutation.names)) { "Catalogue changed" }
                mutation.names.forEach { name ->
                    val old = dao.categoryPreference(mutation.playlistId, name) ?: CategoryPreferenceEntity(mutation.playlistId, name)
                    dao.putCategory(old.copy(hidden = mutation.hidden ?: old.hidden, pinned = mutation.pinned ?: old.pinned))
                }
            }
            is ListMutation.Pin -> setChannelPinned(mutation.scope, mutation.id, mutation.pinned)
            is ListMutation.Icon -> setCategoryIcon(mutation.playlistId, mutation.name, mutation.icon)
            is ListMutation.Binding -> bindEpg(mutation.id, mutation.epg?.sourceId, mutation.epg?.id)
            is ListMutation.ChannelOrder -> {
                val preferences = dao.allPreferences().associateBy { it.channelId }
                val channels = dao.activeChannels().filter { it.playlistId == mutation.scope.playlistId }.map { it.model(preferences[it.id]) }
                val current = ListManagement.channels(channels, mutation.scope)
                require(ListManagement.sameMembers(mutation.ids, current.map { it.id })) { "Catalogue changed" }
                reorderChannelList(mutation.scope, mutation.ids)
            }
            is ListMutation.CategoryOrder -> {
                val names = dao.activeChannels().filter { it.playlistId == mutation.playlistId }.map { it.category }.distinct()
                require(ListManagement.sameMembers(mutation.names, names)) { "Catalogue changed" }
                reorderCategories(mutation.playlistId, mutation.names)
            }
        }
    } }
    override fun categories(playlistId: String?) = catalogue.categories(playlistId)
    override fun programmes(channelIds: List<String>, start: Long, end: Long) = catalogue.programmes(channelIds, start, end)
    override suspend fun importPlaylist(source: PlaylistSource, progress: (ImportProgress) -> Unit) = importer.importPlaylist(source, progress)
    override suspend fun importEpg(source: EpgSource, progress: (ImportProgress) -> Unit) = importer.importEpg(source, progress)
    override suspend fun importProviderPlaylist(profile: ProviderProfile, progress: (ImportProgress) -> Unit) = importer.importProviderPlaylist(profile, progress)
    override suspend fun importProviderEpg(profile: ProviderProfile, progress: (ImportProgress) -> Unit) = importer.importProviderEpg(profile, progress)
    override suspend fun updateProviderProfile(profile: ProviderProfile, progress: (ImportProgress) -> Unit) = importer.updateProviderProfile(profile, progress)
    override suspend fun checkProviderConnection(profile: ProviderProfile) = importer.checkProviderConnection(profile)
    override suspend fun refreshPlaylist(id: String, progress: (ImportProgress) -> Unit) = importer.refreshPlaylist(id, progress)
    override suspend fun refreshEpg(id: String, progress: (ImportProgress) -> Unit) = importer.refreshEpg(id, progress)
    override suspend fun setFavourite(id: String, value: Boolean) { database.withTransaction { dao.putPreference((dao.preference(id) ?: PreferenceEntity(id)).copy(favourite = value)) } }
    override suspend fun setHidden(id: String, value: Boolean) { database.withTransaction { dao.putPreference((dao.preference(id) ?: PreferenceEntity(id)).copy(hidden = value)) } }
    override suspend fun reorderChannels(ids: List<String>, favourites: Boolean) { database.withTransaction { ids.distinct().forEachIndexed { index, id ->
        val p = dao.preference(id) ?: PreferenceEntity(id)
        dao.putPreference(if (favourites) p.copy(favouritePosition = index) else p.copy(position = index))
    } } }
    override suspend fun setCategoryHidden(playlistId: String, name: String, value: Boolean) { database.withTransaction {
        dao.putCategory((dao.categoryPreference(playlistId, name) ?: CategoryPreferenceEntity(playlistId, name)).copy(hidden = value))
    } }
    override suspend fun setCategoryIcon(playlistId: String, name: String, icon: String?) { database.withTransaction {
        dao.putCategory((dao.categoryPreference(playlistId, name) ?: CategoryPreferenceEntity(playlistId, name)).copy(iconOverride = CategoryIcon.fromId(icon)?.id))
    } }
    override suspend fun setCategoryPinned(playlistId: String, name: String, value: Boolean) { database.withTransaction {
        dao.putCategory((dao.categoryPreference(playlistId, name) ?: CategoryPreferenceEntity(playlistId, name)).copy(pinned = value))
    } }
    override suspend fun reorderCategories(playlistId: String, names: List<String>) { database.withTransaction { names.distinct().forEachIndexed { index, name ->
        dao.putCategory((dao.categoryPreference(playlistId, name) ?: CategoryPreferenceEntity(playlistId, name)).copy(position = index))
    } } }
    override suspend fun bindEpg(channelId: String, sourceId: String?, epgChannelId: String?) { database.withTransaction {
        if (sourceId == null || epgChannelId == null) {
            dao.deleteBinding(channelId)
            dao.activeChannels().find { it.id == channelId }?.let { importer.rematch(it.playlistId) }
        } else dao.putBindings(listOf(BindingEntity(channelId, sourceId, epgChannelId, true)))
    } }
    // GLOB with case sets: SQLite's LIKE ignores case for ASCII only, and Cyrillic names must match in any case.
    override suspend fun findEpgChannels(query: String) = dao.findEpg(globContaining(query.take(100))).map { EpgChannel(it.sourceId, it.id, it.name) }
    private val programmeSearch = ProgrammeSearch(dao, runtime)
    override suspend fun searchProgrammes(query: String, playlistId: String?, now: Long): List<Pair<Channel, Programme>> {
        if (query.isBlank()) return emptyList()
        val hidden = categories(playlistId).first().filter { it.hidden }.map { it.playlistId to it.name }.toSet()
        val allowed = channels(playlistId).first().filter { !it.hidden && (it.playlistId to it.group) !in hidden }
        return programmeSearch.search(query, playlistId, now, allowed)
    }
    override suspend fun searchProgrammes(query: String, playlistId: String?, now: Long, channels: List<Channel>) =
        if (query.isBlank()) emptyList() else programmeSearch.search(query, playlistId, now, channels)
    override suspend fun updateSettings(value: AppSettings) = preferences.settings(value)
    override suspend fun changeSettings(change: (AppSettings) -> AppSettings) = preferences.changeSettings(change)
    override suspend fun changeOnboarding(change: (OnboardingState) -> OnboardingState) = preferences.changeOnboarding(change)
    override suspend fun updateOnboarding(value: OnboardingState) = preferences.onboarding(value)
    override suspend fun deletePlaylist(id: String): Unit = imports.withLock {
        database.withTransaction {
            dao.allEpgSources().filter { it.playlistId == id }.forEach { deleteEpgInternal(it.id) }
            dao.deleteListPreferences(id); dao.deletePlaylistBindings(id); dao.deletePreferences(id); dao.deleteChannels(id); dao.deleteCategories(id); dao.deletePlaylist(id)
        }
        val current = settings.first()
        if (current.selectedPlaylistId == id) changeSettings { it.copy(selectedPlaylistId = null, lastChannelId = null, channelSelection = ChannelSelection.All, lastCategory = "Все каналы") }
    }
    private suspend fun deleteEpgInternal(id: String) { dao.deleteSourceBindings(id); dao.pruneProgrammes(id, ""); dao.pruneEpgChannels(id, ""); dao.deleteEpg(id) }
    override suspend fun deleteEpgSource(id: String): Unit = imports.withLock { database.withTransaction {
        val source = dao.epgSource(id); deleteEpgInternal(id); source?.let { importer.rematch(it.playlistId) }
    } }
    override suspend fun updateEpgOffset(id: String, minutes: Int) = imports.withLock {
        database.withTransaction {
            val current = requireNotNull(dao.epgSource(id)) { "Источник EPG не найден" }
            val offset = minutes.coerceIn(-1440, 1440)
            dao.shiftProgrammes(id, current.generation, (offset.toLong() - current.offsetMinutes) * 60_000)
            dao.putEpgSource(current.copy(offsetMinutes = offset))
        }
    }
    override suspend fun refreshStale() {
        importer.refreshMissingEpgIcons()
        refreshScheduled(RefreshKind.PLAYLIST)
        refreshScheduled(RefreshKind.EPG)
    }

    override suspend fun refreshScheduled(kind: RefreshKind): Boolean {
        val ids = if (kind == RefreshKind.PLAYLIST) dao.allPlaylists().map { it.id } else dao.allEpgSources().map { it.id }
        var success = true
        for (id in ids) {
            try {
                importer.refreshDue(id, kind) {
                    val settings = preferences.settings.first()
                    if (kind == RefreshKind.PLAYLIST) settings.playlistRefreshHours else settings.epgRefreshHours
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { success = false }
        }
        return success
    }
}
