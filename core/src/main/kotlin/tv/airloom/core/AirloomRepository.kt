package tv.airloom.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

interface AirloomRepository {
    suspend fun applyListMutation(mutation: ListMutation)
    val providerProfile: Flow<ProviderProfile?>
    val playlists: Flow<List<PlaylistSource>>
    val epgSources: Flow<List<EpgSource>>
    val settings: Flow<AppSettings>
    val onboarding: Flow<OnboardingState>
    fun channels(playlistId: String?, includeHidden: Boolean = false): Flow<List<Channel>>
    fun channelListPreferences(scope: ChannelListScope): Flow<List<ChannelListPreference>>
    suspend fun setChannelPinned(scope: ChannelListScope, channelId: String, value: Boolean)
    suspend fun reorderChannelList(scope: ChannelListScope, ids: List<String>)
    fun categories(playlistId: String?): Flow<List<Category>>
    fun programmes(channelIds: List<String>, start: Long, end: Long): Flow<Map<String, List<Programme>>>
    suspend fun importPlaylist(source: PlaylistSource, progress: (ImportProgress) -> Unit = {}): ImportResult
    suspend fun importEpg(source: EpgSource, progress: (ImportProgress) -> Unit = {}): ImportResult
    suspend fun importProviderPlaylist(profile: ProviderProfile, progress: (ImportProgress) -> Unit = {}): ImportResult
    suspend fun importProviderEpg(profile: ProviderProfile, progress: (ImportProgress) -> Unit = {}): ImportResult
    suspend fun updateProviderProfile(profile: ProviderProfile, progress: (ImportProgress) -> Unit = {})
    suspend fun checkProviderConnection(profile: ProviderProfile)
    suspend fun refreshPlaylist(id: String, progress: (ImportProgress) -> Unit = {}): ImportResult
    suspend fun refreshEpg(id: String, progress: (ImportProgress) -> Unit = {}): ImportResult
    suspend fun setFavourite(id: String, value: Boolean)
    suspend fun setHidden(id: String, value: Boolean)
    suspend fun reorderChannels(ids: List<String>, favourites: Boolean = false)
    suspend fun setCategoryHidden(playlistId: String, name: String, value: Boolean)
    suspend fun setCategoryIcon(playlistId: String, name: String, icon: String?)
    suspend fun setCategoryPinned(playlistId: String, name: String, value: Boolean)
    suspend fun reorderCategories(playlistId: String, names: List<String>)
    suspend fun bindEpg(channelId: String, sourceId: String?, epgChannelId: String?)
    suspend fun findEpgChannels(query: String): List<EpgChannel>
    suspend fun searchProgrammes(query: String, playlistId: String?, now: Long = System.currentTimeMillis()): List<Pair<Channel, Programme>>
    suspend fun searchProgrammes(query: String, playlistId: String?, now: Long, channels: List<Channel>): List<Pair<Channel, Programme>> = searchProgrammes(query, playlistId, now)
    suspend fun refreshScheduled(kind: RefreshKind): Boolean { refreshStale(); return true }
    suspend fun updateSettings(value: AppSettings)
    suspend fun changeSettings(change: (AppSettings) -> AppSettings) = updateSettings(change(settings.first()))
    suspend fun changeOnboarding(change: (OnboardingState) -> OnboardingState) = updateOnboarding(change(onboarding.first()))
    suspend fun updateOnboarding(value: OnboardingState)
    suspend fun deletePlaylist(id: String)
    suspend fun deleteEpgSource(id: String)
    suspend fun updateEpgOffset(id: String, minutes: Int)
    suspend fun refreshStale()
}
