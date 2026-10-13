package tv.airloom.app

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel as EventChannel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.airloom.core.*
import tv.airloom.playback.AirloomPlayer

private const val EXIT_HINT = "Нажмите «Назад» ещё раз, чтобы выйти"
private const val EXIT_BACK_WINDOW_MS = 2_000L
private const val STARTUP_REFRESH_DELAY_MS = 45_000L
private const val NUMBER_ENTRY_MS = 1_500L
private const val NUMBER_MISSING_MS = 1_500L
private const val NUMBER_DIGITS = 4

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AirloomViewModel @Inject constructor(
    private val repository: AirloomRepository,
    @ApplicationContext private val context: Context,
    private val runtime: AirloomRuntime = AirloomRuntime(),
    val playback: tv.airloom.playback.PlaybackController = AirloomPlayer(context, runtime),
    val previews: tv.airloom.playback.FramePreviewService = tv.airloom.playback.PreviewFrames.shared(context),
): ViewModel(), UiActions {
    private val mutableState = MutableStateFlow(UiState(now = runtime.now(), channelLoad = ContentLoad(LoadPhase.LOADING), categoryLoad = ContentLoad(LoadPhase.LOADING)))
    val state = mutableState.asStateFlow()
    private val exitEvents = EventChannel<Unit>(EventChannel.BUFFERED)
    val exits = exitEvents.receiveAsFlow()
    private val startupRetry = MutableStateFlow(0)
    private val catalogueRetry = MutableStateFlow(0)
    private val epgRetry = MutableStateFlow(0)
    private val programmeDayRetry = MutableStateFlow(0)
    private val selectedSource = MutableStateFlow<String?>(null)
    private val visibleIds = MutableStateFlow<List<String>>(emptyList())
    private val query = MutableStateFlow("")
    private val searchRetry = MutableStateFlow(0)
    private val settingsMutex = Mutex()
    private val onboardingMutex = Mutex()
    private var operation: Job? = null
    private var epgSearch: Job? = null
    private var hydrated = false
    private var pendingStartup = true
    private var returningScreen = Screen.GUIDE
    private var pendingSettings: AppSettings? = null
    private var pendingOnboarding: OnboardingState? = null
    private var lastExitBackAtMs = 0L
    private var settingsSequence = 0L
    private var onboardingSequence = 0L
    private val settingsWrites = linkedMapOf<Long, (AppSettings) -> AppSettings>()
    private val onboardingWrites = linkedMapOf<Long, (OnboardingState) -> OnboardingState>()
    private var channelRebuild: Job? = null
    private var numberEntryJob: Job? = null
    /** What was on, and where, when the app went to the background: «Смотреть» on the idle player continues it. */
    private var interrupted: Pair<PlaybackRequest, Long>? = null
    /** The catalogue has answered; the first built list then ends the loading phase. */
    private var catalogueAnswered = false
    /** The published recent list, while the published list is the recent section. */
    private var shownRecent: List<String>? = null
    @Volatile private var operationCommitted = false
    private var cancellationRequested = false

    init {
        viewModelScope.launch {
            startupRetry.flatMapLatest {
                combine(repository.settings, repository.onboarding, repository.providerProfile) { settings, onboarding, provider -> Triple(settings, onboarding, provider) }
                    .onStart { if (!hydrated) mutableState.update { it.copy(loading = true, startupError = null) } }
                    .catch { error ->
                        if (error is CancellationException) throw error
                        mutableState.update { it.copy(loading = false, startupError = FailureKind.STORAGE.userMessage()) }
                    }
            }.collect { (storedSettings, storedOnboarding, provider) ->
                if (settingsWrites.isEmpty() && pendingSettings == storedSettings) pendingSettings = null
                if (onboardingWrites.isEmpty() && pendingOnboarding == storedOnboarding) pendingOnboarding = null
                val settings = pendingSettings ?: storedSettings
                val onboarding = if (provider == null && storedOnboarding.complete) OnboardingState() else pendingOnboarding ?: storedOnboarding
                selectedSource.value = if (provider == null) null else settings.selectedPlaylistId
                mutableState.update {
                    it.copy(settings = settings, onboarding = onboarding, provider = provider, selectedPlaylistId = selectedSource.value,
                        selectedCategory = if (!hydrated) settings.lastCategory else it.selectedCategory,
                        selection = if (!hydrated) settings.channelSelection ?: ChannelSelection.legacy(settings.lastCategory, selectedSource.value.orEmpty()) else it.selection,
                        loading = false, startupError = null, screen = if (!hydrated) { if (provider != null && onboarding.complete && onboarding.step >= 6) Screen.GUIDE else Screen.ONBOARDING } else it.screen)
                }
                val firstHydration = !hydrated
                hydrated = true
                if (firstHydration && provider == null && storedOnboarding.complete) {
                    updateOnboarding(OnboardingState())
                    updateSettings(settings.copy(selectedPlaylistId = null, lastChannelId = null, lastCategory = "Все каналы"))
                }
                rebuildChannels()
                maybeStartup()
                if (firstHydration && onboarding.step in 2..5) {
                    val source = repository.playlists.first().firstOrNull { it.id == onboarding.playlistId }
                    if (source == null) updateOnboarding(onboarding.copy(step = 1))
                    else {
                        val channels = repository.channels(source.id, true).first()
                        val epg = repository.epgSources.first().filter { it.playlistId == source.id }
                        mutableState.update { it.copy(importResult = ImportResult(source.id,
                            channels.size, channels.map { c -> c.group }.distinct().size,
                            channels.count { c -> c.catchup.days > 0 },
                            programmes = epg.sumOf { e -> e.programmeCount },
                            matchedChannels = channels.count { c -> c.epgChannelId != null })) }
                    }
                }
            }
        }
        viewModelScope.launch {
            state.map { it.channelListScope() }.distinctUntilChanged().flatMapLatest { scope ->
                if (scope == null) flowOf(null to emptyList<ChannelListPreference>())
                else repository.channelListPreferences(scope).map { scope to it }
            }.collect { (scope, preferences) ->
                if (scope == state.value.channelListScope()) {
                    if (!state.value.moveSaving && state.value.movingChannelId != null && preferences != state.value.channelListPreferences) finishMove(false)
                    val snapshot = state.value.copy(channelPreferenceScope = scope, channelListPreferences = preferences)
                    val shown = shownRecent
                    val built = withContext(runtime.computation) { buildChannelList(snapshot, shown) }
                    var published = false
                    mutableState.update { current ->
                        published = current.channelListScope() == scope
                        if (!published) current else current.copy(channelPreferenceScope = scope,
                            channelListPreferences = preferences, channels = built.first.sameAs(current.channels), listedSelection = snapshot.channelSelection, focusedChannel = built.second)
                    }
                    if (published) publishedChannels(snapshot, built.first)
                    rebuildChannels()
                }
            }
        }
        viewModelScope.launch { repository.playlists.collect { list -> mutableState.update { it.copy(playlists = list) } } }
        viewModelScope.launch { repository.epgSources.collect { list -> mutableState.update { it.copy(epgSources = list) } } }
        viewModelScope.launch {
            combine(selectedSource, catalogueRetry) { id, _ -> id }.flatMapLatest { id ->
                repository.channels(id, includeHidden = true)
                    .onStart {
                        if (state.value.allChannels.isEmpty()) catalogueAnswered = false
                        mutableState.update { it.copy(channelLoad = ContentLoad(if (it.allChannels.isEmpty()) LoadPhase.LOADING else LoadPhase.READY, it.allChannels.isNotEmpty())) }
                    }
                    .catch { error ->
                        if (error is CancellationException) throw error
                        mutableState.update { it.copy(channelLoad = ContentLoad(LoadPhase.ERROR)) }
                    }
            }.collect { list ->
                if (!state.value.moveSaving && state.value.movingChannelId != null && list != state.value.allChannels) finishMove(false)
                // Loading ends with the first built list, not here: in between the screen would show an empty section.
                catalogueAnswered = true
                mutableState.update { it.copy(allChannels = list, channelLoad = if (it.channelLoad.phase == LoadPhase.LOADING) it.channelLoad else ContentLoad()) }
                rebuildChannels(); maybeStartup()
            }
        }
        viewModelScope.launch {
            combine(selectedSource, catalogueRetry) { id, _ -> id }.flatMapLatest { id ->
                repository.categories(id)
                    .onStart { mutableState.update { it.copy(categoryLoad = ContentLoad(if (it.categories.isEmpty()) LoadPhase.LOADING else LoadPhase.READY, it.categories.isNotEmpty())) } }
                    .catch { error ->
                        if (error is CancellationException) throw error
                        mutableState.update { it.copy(categoryLoad = ContentLoad(LoadPhase.ERROR)) }
                    }
            }.collect { categories ->
                if (!state.value.moveSaving && state.value.movingCategory != null && categories != state.value.categories) finishCategoryMove(false)
                mutableState.update { it.copy(categories = categories, categoryLoad = ContentLoad()) }; rebuildChannels()
            }
        }
        viewModelScope.launch {
            combine(visibleIds, state.map { it.now / 60000 }.distinctUntilChanged(), epgRetry) { ids, minute, _ -> ids to minute }.flatMapLatest { (ids, minute) ->
                val now = minute * 60000
                repository.programmes(ids, now - 3600000, now + 43200000)
                    // Rows already on screen keep their titles while the window moves or the minute changes.
                    .onStart { mutableState.update { it.copy(epgLoad = ContentLoad(if (it.programmes.isEmpty()) LoadPhase.LOADING else LoadPhase.READY)) } }
                    .catch { error ->
                        if (error is CancellationException) throw error
                        mutableState.update { it.copy(epgLoad = ContentLoad(LoadPhase.ERROR)) }
                    }
                    .map { ids to it }
            }.collect { (ids, programmes) -> mutableState.update { it.copy(programmes = programmes, epgAnswered = ids.toSet(), epgLoad = ContentLoad()) } }
        }
        viewModelScope.launch {
            combine(state.map { s ->
                val channel = s.focusedChannel?.takeIf { focused -> s.channels.any { it.id == focused.id } } ?: s.channels.firstOrNull()
                val date = s.programmeBrowser.date.takeIf { s.programmeBrowser.channelId == channel?.id && channel?.let(ProgrammeBrowserPolicy::hasArchive) == true }
                val zone = java.time.ZoneId.systemDefault()
                ProgrammeDayQuery(channel, date ?: ProgrammeBrowserPolicy.today(s.now, zone), zone, s.screen == Screen.GUIDE)
            }.distinctUntilChanged(), programmeDayRetry) { query, retry -> query to retry }
                .collectLatest { (query, _) ->
                    val channel = query.channel ?: return@collectLatest
                    if (!query.active) return@collectLatest
                    mutableState.update { s ->
                        val old = s.programmeBrowser
                        val browser = if (old.channelId != channel.id) ProgrammeBrowserState(channelId = channel.id) else if (!ProgrammeBrowserPolicy.hasArchive(channel)) old.copy(date = null) else old
                        val sameDay = browser.loadedDate == query.date
                        // Today starts from what the channel list already loaded for this channel, so the stage
                        // is not taken apart while the whole day is read; the day then extends these rows.
                        val seed = if (sameDay || query.date != ProgrammeBrowserPolicy.today(s.now, query.zone)) emptyList() else {
                            val (start, end) = ProgrammeBrowserPolicy.window(query.date, query.zone)
                            s.programmes[channel.id].orEmpty().filter { it.end > it.start && it.start < end && it.end > start }
                        }
                        s.copy(programmeBrowser = browser.copy(
                            programmes = if (sameDay) browser.programmes else seed,
                            selected = if (sameDay) browser.selected else null,
                            load = ContentLoad(if (sameDay && browser.programmes.isNotEmpty() || seed.isNotEmpty()) LoadPhase.READY else LoadPhase.LOADING, sameDay),
                        ))
                    }
                    try {
                        val (start, end) = ProgrammeBrowserPolicy.window(query.date, query.zone)
                        repository.programmes(listOf(channel.id), start, end).collect { result ->
                            val rows = withContext(runtime.computation) {
                                result[channel.id].orEmpty().filter { it.end > it.start && it.start < end && it.end > start }
                                    .distinctBy { it.sourceId to it.id }.sortedWith(compareBy<Programme> { it.start }.thenBy { it.id })
                            }
                            mutableState.update { s ->
                                val browser = s.programmeBrowser
                                if (browser.channelId != channel.id || (browser.date ?: ProgrammeBrowserPolicy.today(s.now, query.zone)) != query.date) s
                                else s.copy(programmeBrowser = browser.copy(loadedDate = query.date, programmes = rows,
                                    selected = ProgrammeBrowserPolicy.select(rows, browser.selected, runtime.now(), query.date, query.zone), load = ContentLoad()))
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutableState.update { s -> s.copy(programmeBrowser = s.programmeBrowser.copy(load = ContentLoad(LoadPhase.ERROR))) } }
                }
        }
        viewModelScope.launch {
            playback.state.map { it.copy(positionMs = 0, durationMs = 0) }.distinctUntilChanged().collect { player ->
                mutableState.update { it.copy(player = player) }
            }
        }
        viewModelScope.launch {
            combine(playback.state.map { it.channel?.id }.distinctUntilChanged(), state.map { it.now / 60000 }.distinctUntilChanged()) { id, minute -> id to minute }
                .flatMapLatest { (id, minute) ->
                    val now = minute * 60000
                    if (id == null) flowOf(null to null)
                    // The window reaches a minute past the announcement lead, so the next programme arrives before it is due.
                    else repository.programmes(listOf(id), now, now + UiPolicies.NEXT_PROGRAMME_LEAD_MS + 60000).map {
                        val current = it[id]?.firstOrNull { p -> p.start <= now && p.end > now }
                        current to current?.let { c -> it[id]?.firstOrNull { p -> p.start >= c.end } }
                    }
                }.collect { (programme, next) -> mutableState.update { it.copy(playingProgramme = programme, playingNext = next) } }
        }
        viewModelScope.launch {
            // A null text means the search layer is closed.
            combine(combine(query.debounce(250).distinctUntilChanged(), state.map { it.screen == Screen.SEARCH }.distinctUntilChanged(), searchRetry) { text, active, _ -> text.takeIf { active } },
                state.map { SearchScope(it.selectedPlaylistId, it.allChannels, it.categories, it.settings.recentChannelIds) }.distinctUntilChanged(),
                repository.epgSources, state.map { it.now / 60000 }.distinctUntilChanged()) { text, scope, _, _ -> text to scope }
                .collectLatest { (text, scope) ->
                    val now = state.value.now
                    try {
                        if (text == null) {
                            mutableState.update { it.copy(searchLive = emptyList(), searchArchive = emptyList(), searchLoad = ContentLoad()) }
                            return@collectLatest
                        }
                        val (allowed, allowedIds) = withContext(runtime.computation) {
                            val hiddenGroups = scope.categories.filter { it.hidden }.map { it.playlistId to it.name }.toSet()
                            val allowed = scope.channels.filter { !it.hidden && (it.playlistId to it.group) !in hiddenGroups }
                            allowed to allowed.mapTo(HashSet()) { it.id }
                        }
                        if (text.isBlank()) {
                            // An empty query offers the channels watched last, with what is on them now.
                            val recent = RecentChannels.available(scope.recent, allowed)
                            // A minute tick or a settings change re-runs this; rows already shown keep their programmes until the new ones arrive.
                            mutableState.update {
                                if (it.searchLive.map { result -> result.channel.id } == recent.map { channel -> channel.id }) it.copy(searchArchive = emptyList(), searchLoad = ContentLoad())
                                else it.copy(searchLive = recent.map { channel -> SearchLiveResult(channel) }, searchArchive = emptyList(), searchLoad = ContentLoad())
                            }
                            if (recent.isNotEmpty()) repository.programmes(recent.map { it.id }, now, now + 1).collectLatest { current ->
                                mutableState.update { it.copy(searchLive = recent.map { channel ->
                                    SearchLiveResult(channel, current[channel.id].orEmpty().firstOrNull { p -> p.start <= now && p.end > now }) }) }
                            }
                            return@collectLatest
                        }
                        // Channel names are matched in memory and shown at once; programmes follow from the database.
                        // A re-run for the same query keeps the rows on screen: a focused row must not be removed.
                        val named = withContext(runtime.computation) { SearchResultBuilder.build(text, allowed, emptyList(), emptyMap(), now).live }
                        mutableState.update {
                            if (it.searchLive.isEmpty() && it.searchArchive.isEmpty()) it.copy(searchLive = named, searchLoad = ContentLoad(LoadPhase.LOADING))
                            else it.copy(searchLoad = ContentLoad(LoadPhase.LOADING))
                        }
                        val hits = if (text.length < 2) emptyList() else repository.searchProgrammes(text, scope.playlistId, now, allowed).filter { it.first.id in allowedIds }
                        val candidates = withContext(runtime.computation) { SearchResultBuilder.build(text, allowed, hits, emptyMap(), now).live.map { it.channel.id } }
                        repository.programmes(candidates, now, now + 1).collectLatest { current ->
                            val results = withContext(runtime.computation) { SearchResultBuilder.build(text, allowed, hits, current, now) }
                            mutableState.update { it.copy(searchLive = results.live, searchArchive = results.archive, searchLoad = ContentLoad()) }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        mutableState.update { it.copy(searchLive = emptyList(), searchArchive = emptyList(), searchLoad = ContentLoad(LoadPhase.ERROR)) }
                        showError("Не удалось выполнить поиск. Попробуйте ещё раз.")
                    }
                }
        }

        viewModelScope.launch { while (isActive) { delay(15000); mutableState.update { it.copy(now = runtime.now()) } } }
        viewModelScope.launch {
            state.map { it.message }.distinctUntilChanged().collectLatest { message ->
                if (message != null) { delay(6500); mutableState.update { if (it.message == message) it.copy(message = null) else it } }
            }
        }
        viewModelScope.launch {
            // A stale EPG means parsing and writing the whole guide. Let the catalogue load and the
            // first interactions finish before that work competes for CPU and storage.
            delay(STARTUP_REFRESH_DELAY_MS)
            try { repository.refreshStale() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Source metadata contains a safe error. */ }
        }
    }

    private fun maybeStartup() {
        val s = state.value
        if (!hydrated || !s.onboarding.complete || s.onboarding.step < 6 || !pendingStartup ||
            s.channelLoad.phase != LoadPhase.READY || s.categoryLoad.phase != LoadPhase.READY) return
        pendingStartup = false
        val channel = UiPolicies.lastChannel(s.allChannels, s.categories, s.settings.lastChannelId)
        if (channel == null) {
            mutableState.update { it.copy(screen = Screen.GUIDE) }
            return
        }
        mutableState.update { it.copy(focusedChannel = channel) }
        if (s.settings.startup == StartupMode.FULLSCREEN) {
            returningScreen = Screen.GUIDE
            mutableState.update { it.copy(screen = Screen.PLAYER, playerControls = true) }
        }
    }

    private fun buildChannelList(s: UiState, shownRecent: List<String>?): Triple<List<Channel>, Channel?, Boolean> {
        val hidden = s.categories.filter { it.hidden }.map { it.playlistId to it.name }.toSet()
        var channels = s.allChannels.filter { !it.hidden && (it.playlistId to it.group) !in hidden }
        channels = when (val selection = s.channelSelection) {
            // Playing a channel moves it to the front of the history. In the player, CH+/CH− and the channel
            // panel step through this list, so it keeps its order there and is re-sorted once the player closes.
            ChannelSelection.Recent -> RecentChannels.available(s.settings.recentChannelIds, channels).let {
                if (s.screen == Screen.PLAYER && shownRecent != null) RecentChannels.keepingOrder(it, shownRecent) else it
            }
            ChannelSelection.Favourites -> channels.filter { it.favourite }
            ChannelSelection.All -> channels
            is ChannelSelection.Category -> channels.filter { it.group == selection.name && (selection.playlistId.isBlank() || it.playlistId == selection.playlistId) }
        }
        if (s.channelSelection != ChannelSelection.Recent) channels = ChannelLists.ordered(channels, s.channelListScope(),
            s.channelListPreferences.takeIf { s.channelPreferenceScope == s.channelListScope() }.orEmpty())
        val moveValid = s.movingChannelId == null || s.moveSaving || channels.map { it.id }.toSet() == s.movingIds.toSet()
        if (moveValid && s.movingChannelId != null) {
            val byId = channels.associateBy { it.id }
            channels = s.movingIds.mapNotNull(byId::get)
        }
        val focused = s.focusedChannel?.let { old -> channels.firstOrNull { it.id == old.id } }
            ?: channels.firstOrNull { it.id == s.settings.lastChannelId } ?: channels.firstOrNull()
        return Triple(channels, focused, moveValid)
    }

    private fun publishedChannels(snapshot: UiState, channels: List<Channel>) {
        shownRecent = if (snapshot.channelSelection == ChannelSelection.Recent) channels.map { it.id } else null
    }

    /** An unchanged rebuild keeps the published list instance, so the channel list is not redrawn. */
    private fun List<Channel>.sameAs(published: List<Channel>): List<Channel> = if (this == published) published else this

    private fun rebuildChannels() {
        channelRebuild?.cancel()
        channelRebuild = viewModelScope.launch {
            val s = state.value
            val answered = catalogueAnswered
            val shown = shownRecent
            val built = withContext(runtime.computation) { buildChannelList(s, shown) }
            mutableState.update { current -> current.copy(channels = built.first.sameAs(current.channels), listedSelection = s.channelSelection,
                channelLoad = if (answered && current.channelLoad.phase == LoadPhase.LOADING) ContentLoad() else current.channelLoad,
                focusedChannel = if (current.screen == Screen.PLAYER && current.player.request == null) current.focusedChannel
                    else if (current.focusedChannel != s.focusedChannel) built.first.firstOrNull { it.id == current.focusedChannel?.id } ?: built.second
                    else built.second,
                movingChannelId = current.movingChannelId.takeIf { built.third },
                movingIds = current.movingIds.takeIf { built.third }.orEmpty()) }
            publishedChannels(s, built.first)
            if (visibleIds.value.isEmpty()) visibleIds.value = built.first.take(10).map { it.id }
            maybeStartup()
        }
    }

    private data class SearchScope(val playlistId: String?, val channels: List<Channel>, val categories: List<Category>, val recent: List<String>)
    private data class ProgrammeDayQuery(val channel: Channel?, val date: java.time.LocalDate, val zone: java.time.ZoneId, val active: Boolean)

    override fun selectProgrammeDate(date: java.time.LocalDate) {
        val s = state.value
        val channel = s.focusedChannel ?: s.channels.firstOrNull() ?: return
        val today = ProgrammeBrowserPolicy.today(runtime.now())
        if (date < ProgrammeBrowserPolicy.earliest(channel, runtime.now()) || date > today) return
        mutableState.update { it.copy(programmeBrowser = ProgrammeBrowserState(channelId = channel.id,
            date = date.takeUnless { it == today }, positionRevision = it.programmeBrowser.positionRevision + 1)) }
        programmeDayRetry.value++
    }
    override fun stepProgrammeDay(delta: Int) {
        val date = state.value.programmeBrowser.date ?: ProgrammeBrowserPolicy.today(runtime.now())
        selectProgrammeDate(date.plusDays(delta.coerceIn(-1, 1).toLong()))
    }
    override fun currentProgramme() {
        val s = state.value
        val today = ProgrammeBrowserPolicy.today(runtime.now())
        if (s.programmeBrowser.loadedDate != today) selectProgrammeDate(today)
        else mutableState.update { it.copy(programmeBrowser = it.programmeBrowser.copy(date = null,
            selected = ProgrammeBrowserPolicy.select(it.programmeBrowser.programmes, null, runtime.now(), today, java.time.ZoneId.systemDefault()),
            positionRevision = it.programmeBrowser.positionRevision + 1)) }
    }
    override fun retryProgrammeDay() { programmeDayRetry.value++ }
    override fun selectBrowserProgramme(programme: Programme) {
        mutableState.update { s -> s.copy(programmeBrowser = s.programmeBrowser.copy(selected =
            s.programmeBrowser.programmes.firstOrNull { ProgrammeBrowserPolicy.same(it, programme) } ?: s.programmeBrowser.selected)) }
    }
    override fun playBrowserProgramme(programme: Programme) {
        val s = state.value
        val channel = s.allChannels.firstOrNull { it.id == s.programmeBrowser.channelId } ?: return
        val current = s.programmeBrowser.programmes.firstOrNull { ProgrammeBrowserPolicy.same(it, programme) } ?: return
        selectBrowserProgramme(current)
        if (current.start >= runtime.now()) {
            mutableState.update { it.copy(detailChannel = channel, detailProgramme = current) }
        } else if (current.end > runtime.now() && !ProgrammeBrowserPolicy.hasArchive(channel)) play(channel)
        else play(channel, current)
    }

    override fun retryStartup() { startupRetry.value++; catalogueRetry.value++ }
    override fun retryCatalogue() { catalogueRetry.value++ }
    override fun retryEpg() { epgRetry.value++ }

    override fun navigate(screen: Screen) {
        if (state.value.moveSaving) return
        finishMove(false); finishCategoryMove(false)
        if (screen == Screen.PLAYER && state.value.player.channel == null) return
        lastExitBackAtMs = 0L
        val leavingPlayer = screen != Screen.PLAYER && state.value.screen == Screen.PLAYER
        if (leavingPlayer) { playback.stop(); interrupted = null; numberEntryJob?.cancel() }
        mutableState.update { it.copy(screen = screen, quickChannels = if (screen == Screen.PLAYER) it.quickChannels else false,
            numberEntry = if (screen == Screen.PLAYER) it.numberEntry else null,
            error = null, message = it.message.takeUnless { message -> message == EXIT_HINT }, detailChannel = null, detailProgramme = null) }
        if (leavingPlayer) rebuildChannels()
    }
    override fun back() {
        val s = state.value
        val atExit = s.detailChannel == null && s.movingChannelId == null && s.movingCategory == null &&
            (s.screen == Screen.GUIDE || s.screen == Screen.ONBOARDING && !s.busy && s.onboarding.step == 0)
        if (!atExit) lastExitBackAtMs = 0L
        when {
            s.detailChannel != null -> dismissDetail()
            s.movingCategory != null -> finishCategoryMove(false)
            s.movingChannelId != null -> finishMove(false)
            s.screen == Screen.PLAYER && s.quickChannels -> showQuickChannels(false)
            s.screen == Screen.PLAYER && s.player.request == null -> navigate(Screen.GUIDE)
            s.screen == Screen.PLAYER -> navigate(returningScreen)
            s.screen == Screen.ONBOARDING && s.busy -> cancelImport()
            s.screen == Screen.ONBOARDING && s.onboarding.step > 0 -> {
                if (s.onboarding.complete && s.onboarding.step == 1) {
                    updateOnboarding(s.onboarding.copy(step = 6)); navigate(Screen.SETTINGS)
                } else updateOnboarding(s.onboarding.copy(step = when (s.onboarding.step) { 2 -> 1; else -> s.onboarding.step - 1 }))
            }
            s.screen != Screen.GUIDE && s.screen != Screen.ONBOARDING -> navigate(Screen.GUIDE)
            else -> requestExit()
        }
    }
    private fun requestExit() {
        val now = SystemClock.elapsedRealtime()
        if (lastExitBackAtMs > 0 && now - lastExitBackAtMs <= EXIT_BACK_WINDOW_MS) {
            lastExitBackAtMs = 0L
            playback.stop()
            viewModelScope.launch { exitEvents.send(Unit) }
        } else {
            lastExitBackAtMs = now
            mutableState.update { it.copy(message = EXIT_HINT) }
            viewModelScope.launch {
                delay(EXIT_BACK_WINDOW_MS)
                if (lastExitBackAtMs == now) {
                    lastExitBackAtMs = 0L
                    mutableState.update { state -> state.copy(message = state.message.takeUnless { it == EXIT_HINT }) }
                }
            }
        }
    }
    override fun dismissMessage() { mutableState.update { it.copy(message = null, error = null) } }
    override fun updateOnboarding(value: OnboardingState) {
        val previous = state.value.onboarding
        val change: (OnboardingState) -> OnboardingState = { current -> current.copy(
            complete = if (value.complete != previous.complete) value.complete else current.complete,
            step = if (value.step != previous.step) value.step else current.step,
            playlistId = if (value.playlistId != previous.playlistId) value.playlistId else current.playlistId,
            provider = if (value.provider != previous.provider) value.provider else current.provider,
        ) }
        val sequence = ++onboardingSequence
        onboardingWrites[sequence] = change
        pendingOnboarding = value
        mutableState.update { it.copy(onboarding = value, onboardingSaving = true) }
        viewModelScope.launch {
            onboardingMutex.withLock {
                try { repository.changeOnboarding(change) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { showError(FailureKind.STORAGE.userMessage()) }
                finally {
                    onboardingWrites.remove(sequence)
                    val stored = try { repository.onboarding.first() } catch (_: Exception) { previous }
                    val effective = onboardingWrites.values.fold(stored) { current, pending -> pending(current) }
                    pendingOnboarding = effective.takeIf { onboardingWrites.isNotEmpty() }
                    mutableState.update { it.copy(onboarding = effective, onboardingSaving = onboardingWrites.isNotEmpty()) }
                }
            }
        }
    }
    override fun nextOnboarding() {
        val o = state.value.onboarding
        when (o.step) { 1 -> importOnboardingPlaylist(); 3 -> importOnboardingEpg(); 5 -> finishOnboarding(); else -> updateOnboarding(o.copy(step = (o.step + 1).coerceAtMost(5))) }
    }
    override fun importOnboardingPlaylist() {
        val o = state.value.onboarding
        if (o.provider.key.isBlank()) { showError("Укажите ключ IPTV.ONLINE"); return }
        val id = "iptv-online"
        updateOnboarding(o.copy(step = 2, playlistId = id))
        runOperation { reportProgress ->
            val result = repository.importProviderPlaylist(o.provider, reportProgress)
            mutableState.update { it.copy(importResult = result) }
            updateOnboarding(state.value.onboarding.copy(step = 2, playlistId = id))
            selectPlaylist(id)
        }
    }
    override fun importOnboardingEpg() {
        val o = state.value.onboarding
        val playlistId = o.playlistId
        if (playlistId == null) { skipEpg(); return }
        runOperation { reportProgress ->
            val result = repository.importProviderEpg(o.provider, reportProgress)
            mutableState.update {
                val playlistResult = it.importResult
                it.copy(importResult = playlistResult?.copy(
                    programmes = result.programmes,
                    matchedChannels = result.matchedChannels,
                    warnings = playlistResult.warnings + result.warnings
                ) ?: result, message = "Телепрограмма загружена: ${result.programmes} передач")
            }
            updateOnboarding(state.value.onboarding.copy(step = 3))
        }
    }
    override fun skipEpg() { if (state.value.busy) cancelImport(); updateOnboarding(state.value.onboarding.copy(step = 4)); dismissMessage() }
    override fun finishOnboarding() {
        if (state.value.playlists.isEmpty()) { showError("Сначала добавьте плейлист"); return }
        updateOnboarding(state.value.onboarding.copy(complete = true, step = 6, provider = ProviderProfile()))
        pendingStartup = false
        navigate(Screen.GUIDE)
    }
    override fun cancelImport() {
        if (operation == null) return
        cancellationRequested = true
        operation?.cancel()
        mutableState.update { it.copy(progress = ImportProgress("Отмена…")) }
    }
    override fun selectPlaylist(id: String?) {
        selectedSource.value = id; visibleIds.value = emptyList()
        mutableState.update { it.copy(selectedPlaylistId = id, selectedCategory = "Все каналы", selection = ChannelSelection.All, focusedChannel = null, programmes = emptyMap()) }
        updateSettings(state.value.settings.copy(selectedPlaylistId = id, lastCategory = "Все каналы", channelSelection = ChannelSelection.All))
        // The same source emits nothing new, and the list must still follow the selection.
        rebuildChannels()
    }
    override fun selectCategory(name: String) = select(ChannelSelection.legacy(name, state.value.selectedPlaylistId.orEmpty()))
    override fun selectCategory(category: Category) = select(ChannelSelection.Category(category.playlistId, category.name))
    private fun select(selection: ChannelSelection) {
        if (state.value.moveSaving) return
        finishCategoryMove(false)
        mutableState.update { it.copy(selectedCategory = selection.label, selection = selection, focusedChannel = null, movingChannelId = null, movingIds = emptyList()) }
        visibleIds.value = emptyList()
        rebuildChannels()
        changeSettings { it.copy(lastCategory = selection.label, channelSelection = selection) }
    }
    override fun setVisibleChannels(ids: List<String>) { visibleIds.value = ids.distinct().take(40) }
    override fun focusProgramme(channel: Channel, programme: Programme?) { mutableState.update { it.copy(focusedChannel = channel, focusedProgramme = programme) } }
    override fun activate(channel: Channel, programme: Programme?) {
        val now = runtime.now()
        if (programme != null && (programme.end <= now || programme.start > now)) {
            mutableState.update { it.copy(detailChannel = channel, detailProgramme = programme) }; return
        }
        play(channel)
    }
    override fun play(channel: Channel, programme: Programme?) {
        if (programme != null) {
            val available = CatchupResolver.resolve(PlaybackRequest.Archive(channel, programme), runtime.now())
            if (available is CatchupResult.Unavailable) { notice(available.reason); return }
        }
        previews.stop()
        interrupted = null
        numberEntryJob?.cancel()
        playback.configure(state.value.settings.playbackNetwork)
        playback.play(if (programme == null) PlaybackRequest.Live(channel) else PlaybackRequest.Archive(channel, programme))
        val player = playback.state.value
        // The channel list follows the channel being watched, so leaving the player returns to it, not to the row it was opened from.
        mutableState.update { it.copy(player = player, detailChannel = null, detailProgramme = null, playerControls = true, quickChannels = false, numberEntry = null, error = player.error,
            focusedChannel = it.channels.firstOrNull { listed -> listed.id == channel.id } ?: it.focusedChannel) }
        changeSettings { it.copy(lastChannelId = channel.id, recentChannelIds = RecentChannels.opened(it.recentChannelIds, channel.id)) }
        rebuildChannels()
        fullscreen()
    }
    override fun fullscreen() {
        if (state.value.player.channel == null) return
        if (state.value.screen != Screen.PLAYER) returningScreen = state.value.screen
        mutableState.update { it.copy(screen = Screen.PLAYER, playerControls = true, quickChannels = false) }
    }
    private var managerPreferencesJob: Job? = null
    override fun openManagerScope(scope: ChannelListScope?) {
        managerPreferencesJob?.cancel()
        mutableState.update { it.copy(managerScope = scope, managerPreferences = emptyList(), managerLoading = scope != null, managerError = null) }
        if (scope != null) managerPreferencesJob = viewModelScope.launch {
            try {
                repository.channelListPreferences(scope).collect { preferences ->
                    mutableState.update { it.copy(managerPreferences = preferences, managerLoading = false) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(managerLoading = false, managerError = "Не удалось загрузить порядок списка") } }
        }
    }
    override fun mutateList(mutation: ListMutation) {
        if (state.value.managerSaving) return
        mutableState.update { it.copy(managerSaving = true, managerError = null) }
        viewModelScope.launch {
            try {
                repository.applyListMutation(mutation)
                // Publish the committed snapshot before reporting success to the editor.
                val all = repository.channels(state.value.selectedPlaylistId, includeHidden = true).first()
                val categories = repository.categories(state.value.selectedPlaylistId).first()
                val scope = state.value.managerScope
                val preferences = scope?.let { repository.channelListPreferences(it).first() }.orEmpty()
                mutableState.update { it.copy(allChannels = all, categories = categories,
                    managerPreferences = preferences, managerSaving = false, managerRevision = it.managerRevision + 1) }
                rebuildChannels()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(managerSaving = false,
                managerError = "Не удалось сохранить изменения. Если список обновился, откройте его заново и повторите.") } }
        }
    }
    override fun dismissDetail() { mutableState.update { it.copy(detailChannel = null, detailProgramme = null) } }
    override fun setFavourite(channel: Channel, value: Boolean) = launchMutation { repository.setFavourite(channel.id, value) }
    override fun setHidden(channel: Channel, value: Boolean) = launchMutation { repository.setHidden(channel.id, value) }
    override fun setChannelPinned(channel: Channel, value: Boolean) {
        val scope = state.value.channelListScope() ?: return
        launchMutation { repository.setChannelPinned(scope, channel.id, value) }
    }
    override fun startMove(channel: Channel) {
        val s = state.value
        if (s.moveSaving || s.channelListScope() == null || channel.hidden || s.channels.none { it.id == channel.id }) return
        finishCategoryMove(false)
        mutableState.update { it.copy(movingChannelId = channel.id, movingIds = it.channels.map { c -> c.id }, focusedChannel = channel) }
    }
    override fun moveChannel(delta: Int) {
        val s = state.value
        if (s.moveSaving) return
        val id = s.movingChannelId ?: return
        val eligible = s.channels.filter { s.channelPinned(it.id) == s.channelPinned(id) }.map { it.id }.toSet()
        mutableState.update { it.copy(movingIds = ChannelLists.moved(s.movingIds, id, delta, eligible)) }; rebuildChannels()
    }
    override fun finishMove(save: Boolean) {
        val s = state.value
        if (s.movingChannelId == null || s.moveSaving) return
        if (!save) { mutableState.update { it.copy(movingChannelId = null, movingIds = emptyList()) }; rebuildChannels(); return }
        val scope = s.channelListScope() ?: return
        mutableState.update { it.copy(moveSaving = true) }
        viewModelScope.launch {
            try {
                repository.reorderChannelList(scope, s.movingIds)
                val preferences = repository.channelListPreferences(scope).first()
                mutableState.update { it.copy(channelPreferenceScope = scope, channelListPreferences = preferences, movingChannelId = null, movingIds = emptyList(), moveSaving = false) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutableState.update { it.copy(movingChannelId = null, movingIds = emptyList(), moveSaving = false) }
                showError("Не удалось сохранить порядок каналов")
            }
            rebuildChannels()
        }
    }
    override fun startCategoryMove(category: Category) {
        val s = state.value
        if (s.moveSaving || category.hidden) return
        finishMove(false)
        mutableState.update { it.copy(movingCategory = category, movingCategoryNames = it.categories.filter { c -> c.playlistId == category.playlistId }.sortedBy { c -> c.order }.map { c -> c.name }) }
    }
    override fun moveCategoryRow(delta: Int) {
        val s = state.value
        val category = s.movingCategory ?: return
        if (s.moveSaving) return
        val eligible = s.categories.filter { it.playlistId == category.playlistId && !it.hidden && it.pinned == category.pinned }.map { it.name }.toSet()
        mutableState.update { it.copy(movingCategoryNames = ChannelLists.moved(s.movingCategoryNames, category.name, delta, eligible)) }
    }
    override fun finishCategoryMove(save: Boolean) {
        val s = state.value
        val category = s.movingCategory ?: return
        if (s.moveSaving) return
        if (!save) { mutableState.update { it.copy(movingCategory = null, movingCategoryNames = emptyList()) }; return }
        mutableState.update { it.copy(moveSaving = true) }
        viewModelScope.launch {
            try {
                repository.reorderCategories(category.playlistId, s.movingCategoryNames)
                val categories = repository.categories(s.selectedPlaylistId).first()
                mutableState.update { it.copy(categories = categories, movingCategory = null, movingCategoryNames = emptyList(), moveSaving = false) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutableState.update { it.copy(movingCategory = null, movingCategoryNames = emptyList(), moveSaving = false) }
                showError("Не удалось сохранить порядок категорий")
            }
        }
    }
    override fun setCategoryHidden(category: Category, value: Boolean) = launchMutation { repository.setCategoryHidden(category.playlistId, category.name, value) }
    override fun setCategoryIcon(category: Category, icon: String?) = launchMutation { repository.setCategoryIcon(category.playlistId, category.name, icon) }
    override fun setCategoryPinned(category: Category, value: Boolean) = launchMutation { repository.setCategoryPinned(category.playlistId, category.name, value) }
    override fun moveCategory(category: Category, delta: Int) = launchMutation {
        val names = state.value.categories.filter { it.playlistId == category.playlistId }.sortedBy { it.order }.map { it.name }.toMutableList()
        val index = names.indexOf(category.name)
        if (index >= 0) { val name = names.removeAt(index); names.add((index + delta).coerceIn(0, names.size), name); repository.reorderCategories(category.playlistId, names) }
    }
    override fun search(query: String) {
        val current = state.value
        // The same text again changes nothing, except after a failure, where it is a retry.
        if (query == current.searchQuery) { if (current.searchLoad.phase != LoadPhase.ERROR) return else searchRetry.value++ }
        mutableState.update { it.copy(searchQuery = query, searchLive = emptyList(), searchArchive = emptyList(), searchLoad = ContentLoad(if (query.isBlank()) LoadPhase.READY else LoadPhase.LOADING)) }
        this.query.value = query
    }
    override fun findEpg(query: String) {
        epgSearch?.cancel(); epgSearch = viewModelScope.launch {
            try { delay(200); val result = repository.findEpgChannels(query); mutableState.update { it.copy(epgCandidates = result) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showError(FailureKind.OPERATION.userMessage()) }
        }
    }
    override fun bindEpg(channel: Channel, epg: EpgChannel?) = launchMutation {
        repository.bindEpg(channel.id, epg?.sourceId, epg?.id)
        mutableState.update { it.copy(message = if (epg == null) "Автоматическая привязка восстановлена" else "Телепрограмма привязана") }
    }
    override fun updateSettings(value: AppSettings) = changeSettings(value.patch(state.value.settings))
    override fun changeSettings(change: (AppSettings) -> AppSettings) {
        val previous = state.value.settings
        val sequence = ++settingsSequence
        settingsWrites[sequence] = change
        val optimistic = change(previous)
        pendingSettings = optimistic
        mutableState.update { it.copy(settings = optimistic, settingsSaving = true) }
        viewModelScope.launch {
            settingsMutex.withLock {
                try { repository.changeSettings(change) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { showError(FailureKind.STORAGE.userMessage()) }
                finally {
                    settingsWrites.remove(sequence)
                    val stored = try { repository.settings.first() } catch (_: Exception) { previous }
                    val effective = settingsWrites.values.fold(stored) { current, pending -> pending(current) }
                    pendingSettings = effective.takeIf { settingsWrites.isNotEmpty() }
                    mutableState.update { it.copy(settings = effective, settingsSaving = settingsWrites.isNotEmpty()) }
                    rebuildChannels()
                }
            }
        }
    }
    override fun saveProvider(profile: ProviderProfile) = runOperation { reportProgress ->
        repository.updateProviderProfile(profile, reportProgress)
        mutableState.update { it.copy(providerSaveRevision = it.providerSaveRevision + 1) }
        notice("Настройки провайдера сохранены")
    }
    override fun saveProviderEpg(profile: ProviderProfile) = runOperation { reportProgress ->
        val current = requireNotNull(state.value.provider) { "Профиль не настроен" }
        val result = repository.importProviderEpg(current.copy(epgOrigin = profile.epgOrigin, epgVariant = profile.epgVariant), reportProgress)
        mutableState.update { it.copy(providerSaveRevision = it.providerSaveRevision + 1) }
        notice("Телепрограмма обновлена: ${result.programmes} передач")
    }
    override fun checkProvider(profile: ProviderProfile) = runOperation { _ ->
        repository.checkProviderConnection(profile)
        notice("Соединение с плейлистом работает")
    }
    override fun refreshPlaylist(id: String) = runOperation { reportProgress -> val result = repository.refreshPlaylist(id, reportProgress); notice("Обновлено каналов: ${result.channels}") }
    override fun refreshEpg(id: String) = runOperation { reportProgress -> val result = repository.refreshEpg(id, reportProgress); notice("Обновлено передач: ${result.programmes}") }
    override fun updateEpgOffset(id: String, minutes: Int) = launchMutation { repository.updateEpgOffset(id, minutes) }
    override fun showPlayerControls(show: Boolean) { mutableState.update { it.copy(playerControls = show) } }
    override fun showQuickChannels(show: Boolean) {
        if (state.value.screen != Screen.PLAYER) return
        mutableState.update { it.copy(quickChannels = show, playerControls = if (show) false else it.playerControls) }
    }
    override fun playerTogglePause() {
        val resume = interrupted
        if (state.value.player.request == null && resume != null) {
            val (request, position) = resume
            play(request.channel, (request as? PlaybackRequest.Archive)?.programme)
            // The archive goes on from where it was left, when the stream can be opened at that point.
            if (request is PlaybackRequest.Archive && position > 0 && playback.state.value.request == request) playback.seekTo(position)
        } else if (state.value.player.request == null) state.value.focusedChannel?.let { play(it) }
        else if (state.value.player.playWhenReady || state.value.player.isPlaying) playback.pause() else playback.resume()
    }
    override fun playerRetry() {
        playback.configure(state.value.settings.playbackNetwork)
        playback.retry()
    }
    override fun playerRestart() {
        val request = state.value.player.request ?: return
        playback.configure(state.value.settings.playbackNetwork)
        playback.play(request)
    }
    override fun playerNetworkSettings() {
        navigate(Screen.SETTINGS)
        mutableState.update { it.copy(settingsSection = SettingsSection.NETWORK) }
    }
    override fun playerSeek(deltaMs: Long) = playback.seekBy(deltaMs)
    override fun playerSeekTo(positionMs: Long) = playback.seekTo(positionMs)
    override fun playerLive() = playback.goLive()
    override fun playerFromStart() {
        val channel = state.value.player.channel ?: return
        viewModelScope.launch {
            val now = runtime.now()
            val programme = repository.programmes(listOf(channel.id), now - 86400000, now + 1).first()[channel.id]?.firstOrNull { it.start <= now && it.end > now }
            if (programme == null) showError("Для просмотра с начала нужна телепрограмма") else play(channel, programme)
        }
    }
    override fun playerAudio(id: String) = playback.selectAudio(id)
    override fun nextChannel(delta: Int) {
        val channels = state.value.channels
        if (channels.isEmpty()) return
        val current = channels.indexOfFirst { it.id == state.value.player.channel?.id }
        // A channel from outside the section (opened from search) steps to the section's first or last channel.
        val next = if (current < 0) (if (delta > 0) delta - 1 else delta) else current + delta
        play(channels[next.mod(channels.size)])
    }
    private fun numbered(number: Int): Channel? {
        val s = state.value
        val hiddenGroups = s.categories.filter { it.hidden }.map { it.playlistId to it.name }.toSet()
        return s.allChannels.firstOrNull { it.number == number && !it.hidden && (it.playlistId to it.group) !in hiddenGroups }
    }
    override fun channelDigit(digit: Int) {
        val s = state.value
        if (s.screen != Screen.PLAYER || digit !in 0..9) return
        val digits = (s.numberEntry?.takeUnless { it.missing }?.digits.orEmpty() + digit).trimStart('0').takeLast(NUMBER_DIGITS)
        if (digits.isEmpty()) return
        mutableState.update { it.copy(numberEntry = NumberEntry(digits, numbered(digits.toInt())?.name)) }
        numberEntryJob?.cancel()
        numberEntryJob = viewModelScope.launch { delay(NUMBER_ENTRY_MS); confirmChannelNumber() }
    }
    override fun confirmChannelNumber() {
        val entry = state.value.numberEntry?.takeUnless { it.missing } ?: return
        val channel = numbered(entry.digits.toInt())
        val waiting = numberEntryJob
        if (channel == null) {
            mutableState.update { it.copy(numberEntry = entry.copy(missing = true)) }
            numberEntryJob = viewModelScope.launch { delay(NUMBER_MISSING_MS); mutableState.update { it.copy(numberEntry = null) } }
        } else if (channel.id == state.value.player.channel?.id && state.value.player.request is PlaybackRequest.Live && state.value.player.error == null) {
            mutableState.update { it.copy(numberEntry = null) }
        } else play(channel)
        waiting?.cancel()
    }
    override fun cancelChannelNumber() { numberEntryJob?.cancel(); mutableState.update { it.copy(numberEntry = null) } }
    /** Playback stops, but the player stays: on return it shows the channel and continues from «Смотреть». */
    fun background() {
        previews.stop()
        val playing = playback.state.value
        val request = playing.request.takeIf { state.value.screen == Screen.PLAYER }
        if (request != null) interrupted = request to playing.positionMs
        playback.stop()
        numberEntryJob?.cancel()
        mutableState.update { it.copy(player = playback.state.value, quickChannels = false, numberEntry = null,
            playerControls = if (it.screen == Screen.PLAYER) true else it.playerControls,
            focusedChannel = request?.channel ?: it.focusedChannel) }
    }
    fun showError(message: String) { mutableState.update { it.copy(error = message) } }
    private fun notice(message: String) { mutableState.update { it.copy(message = message) } }
    private fun launchMutation(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { showError(errorText(e)) } } }
    private fun runOperation(block: suspend ((ImportProgress) -> Unit) -> Unit) {
        // busy is cleared only when the running operation finishes, so operations never overlap.
        if (state.value.busy) return
        operationCommitted = false
        cancellationRequested = false
        mutableState.update { it.copy(busy = true, error = null, message = null, progress = ImportProgress("Подключение…")) }
        operation = viewModelScope.launch {
            try {
                block { progress ->
                    if (progress.committed) operationCommitted = true
                    mutableState.update { it.copy(progress = progress) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(errorText(e)) }
            finally {
                operation = null
                mutableState.update { it.copy(busy = false, message = if (cancellationRequested) {
                    if (operationCommitted) "Изменения сохранены" else "Импорт отменён. Последняя рабочая версия сохранена."
                } else it.message) }
                if (cancellationRequested && !operationCommitted && state.value.screen == Screen.ONBOARDING && state.value.onboarding.step == 2 && state.value.importResult == null) updateOnboarding(state.value.onboarding.copy(step = 1))
            }
        }
    }
    private fun errorText(error: Exception): String = when (error) {
        is AirloomFailure -> error.kind.userMessage()
        is IllegalArgumentException -> FailureKind.INVALID_PROFILE.userMessage()
        is SecurityException -> FailureKind.ACCESS.userMessage()
        is java.io.IOException -> FailureKind.NETWORK.userMessage()
        else -> FailureKind.OPERATION.userMessage()
    }
    override fun onCleared() { previews.stop(); playback.release(); super.onCleared() }
}
