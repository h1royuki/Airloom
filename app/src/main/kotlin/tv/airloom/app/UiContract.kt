package tv.airloom.app

import tv.airloom.core.*
import tv.airloom.playback.PlaybackState

enum class LoadPhase { LOADING, READY, ERROR }
data class ContentLoad(val phase: LoadPhase = LoadPhase.READY, val refreshing: Boolean = false)

enum class SettingsSection { CONNECTION, EPG, APPEARANCE, PLAYBACK, NETWORK, LISTS, ABOUT }

enum class Screen { ONBOARDING, GUIDE, SETTINGS, SEARCH, PLAYER }
data class UiState(
    val loading: Boolean = true,
    val startupError: String? = null,
    val channelLoad: ContentLoad = ContentLoad(),
    val categoryLoad: ContentLoad = ContentLoad(),
    val epgLoad: ContentLoad = ContentLoad(),
    val programmeBrowser: ProgrammeBrowserState = ProgrammeBrowserState(),
    val searchLoad: ContentLoad = ContentLoad(),
    val settingsSection: SettingsSection = SettingsSection.CONNECTION,
    val providerSaveRevision: Long = 0,
    val managerScope: ChannelListScope? = null,
    val managerPreferences: List<ChannelListPreference> = emptyList(),
    val managerLoading: Boolean = false,
    val managerSaving: Boolean = false,
    val managerError: String? = null,
    val managerRevision: Long = 0,
    val settingsSaving: Boolean = false,
    val onboardingSaving: Boolean = false,
    val screen: Screen = Screen.ONBOARDING,
    val onboarding: OnboardingState = OnboardingState(),
    val provider: ProviderProfile? = null,
    val settings: AppSettings = AppSettings(),
    val playlists: List<PlaylistSource> = emptyList(),
    val epgSources: List<EpgSource> = emptyList(),
    val allChannels: List<Channel> = emptyList(),
    val channels: List<Channel> = emptyList(),
    /** The selection [channels] was built for. While it differs from the current one the rows on screen are stale. Null: not tracked. */
    val listedSelection: ChannelSelection? = null,
    val categories: List<Category> = emptyList(),
    val selectedPlaylistId: String? = null,
    val selectedCategory: String = "Все каналы",
    val selection: ChannelSelection? = null,
    val programmes: Map<String, List<Programme>> = emptyMap(),
    /** Channels the last EPG window answered for; a channel outside it has no verdict yet. Null: every channel is answered. */
    val epgAnswered: Set<String>? = null,
    val focusedChannel: Channel? = null,
    val focusedProgramme: Programme? = null,
    val detailChannel: Channel? = null,
    val detailProgramme: Programme? = null,
    val busy: Boolean = false,
    val progress: ImportProgress = ImportProgress(),
    val importResult: ImportResult? = null,
    val error: String? = null,
    val message: String? = null,
    val searchQuery: String = "",
    val searchLive: List<SearchLiveResult> = emptyList(),
    val searchArchive: List<Pair<Channel, Programme>> = emptyList(),
    val epgCandidates: List<EpgChannel> = emptyList(),
    val player: PlaybackState = PlaybackState(),
    val playingProgramme: Programme? = null,
    val playingNext: Programme? = null,
    val playerControls: Boolean = true,
    val quickChannels: Boolean = false,
    val movingChannelId: String? = null,
    val movingIds: List<String> = emptyList(),
    val movingCategory: Category? = null,
    val movingCategoryNames: List<String> = emptyList(),
    val moveSaving: Boolean = false,
    val channelPreferenceScope: ChannelListScope? = null,
    val channelListPreferences: List<ChannelListPreference> = emptyList(),
    val now: Long = System.currentTimeMillis(),
)

interface UiActions {
    fun openManagerScope(scope: ChannelListScope?) {}
    fun mutateList(mutation: ListMutation) {}

    fun navigate(screen: Screen)
    fun back()
    fun dismissMessage()
    fun updateOnboarding(value: OnboardingState)
    fun nextOnboarding()
    fun importOnboardingPlaylist()
    fun importOnboardingEpg()
    fun skipEpg()
    fun finishOnboarding()
    fun cancelImport()
    fun selectPlaylist(id: String?)
    fun selectCategory(name: String)
    fun selectCategory(category: Category) = selectCategory(category.name)
    fun retryStartup()
    fun retryCatalogue()
    fun selectProgrammeDate(date: java.time.LocalDate)
    fun stepProgrammeDay(delta: Int)
    fun currentProgramme()
    fun retryProgrammeDay()
    fun selectBrowserProgramme(programme: Programme)
    fun playBrowserProgramme(programme: Programme)
    fun retryEpg()
    fun setVisibleChannels(ids: List<String>)
    fun focusProgramme(channel: Channel, programme: Programme?)
    fun activate(channel: Channel, programme: Programme?)
    fun play(channel: Channel, programme: Programme? = null)
    fun fullscreen()
    fun dismissDetail()
    fun setFavourite(channel: Channel, value: Boolean)
    fun setHidden(channel: Channel, value: Boolean)
    fun setChannelPinned(channel: Channel, value: Boolean)
    fun startCategoryMove(category: Category)
    fun moveCategoryRow(delta: Int)
    fun finishCategoryMove(save: Boolean)
    fun startMove(channel: Channel)
    fun moveChannel(delta: Int)
    fun finishMove(save: Boolean)
    fun setCategoryHidden(category: Category, value: Boolean)
    fun setCategoryIcon(category: Category, icon: String?)
    fun setCategoryPinned(category: Category, value: Boolean)
    fun moveCategory(category: Category, delta: Int)
    fun search(query: String)
    fun findEpg(query: String)
    fun bindEpg(channel: Channel, epg: EpgChannel?)
    fun updateSettings(value: AppSettings)
    fun changeSettings(change: (AppSettings) -> AppSettings)
    fun saveProvider(profile: ProviderProfile)
    fun saveProviderEpg(profile: ProviderProfile)
    fun checkProvider(profile: ProviderProfile)
    fun refreshPlaylist(id: String)
    fun refreshEpg(id: String)
    fun updateEpgOffset(id: String, minutes: Int)
    fun showPlayerControls(show: Boolean)
    fun showQuickChannels(show: Boolean)
    fun playerTogglePause()
    fun playerNetworkSettings()
    fun playerRestart()
    fun playerRetry()
    fun playerSeek(deltaMs: Long)
    fun playerSeekTo(positionMs: Long)
    fun playerLive()
    fun playerFromStart()
    fun playerAudio(id: String)
    fun nextChannel(delta: Int)
}

val UiState.channelSelection: ChannelSelection get() = selection ?: ChannelSelection.legacy(selectedCategory, selectedPlaylistId.orEmpty())
fun UiState.channelListScope(): ChannelListScope? = ChannelLists.scope(selectedPlaylistId ?: allChannels.firstOrNull()?.playlistId ?: playlists.firstOrNull()?.id, channelSelection)
fun UiState.channelPinned(id: String): Boolean = channelListScope() == channelPreferenceScope && channelListPreferences.any { it.channelId == id && it.pinned }
