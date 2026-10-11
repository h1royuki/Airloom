package tv.airloom.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import tv.airloom.core.*
import java.io.IOException
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesStore @Inject constructor(private val store: DataStore<Preferences>) {
    private val data = store.data.catch { if (it is IOException) throw AirloomFailure(FailureKind.STORAGE) else throw it }
    val settings = data.map(::readSettings)
    private fun readSettings(p: Preferences) = AppSettings(
        playbackNetwork = PlaybackNetworkSettings(
            reconnectAttempts = p[intPreferencesKey("playback.reconnectAttempts")] ?: 5,
            retryDelayMs = p[intPreferencesKey("playback.retryDelayMs")] ?: 2000,
            connectTimeoutMs = p[intPreferencesKey("playback.connectTimeoutMs")] ?: 15000,
            readTimeoutMs = p[intPreferencesKey("playback.readTimeoutMs")] ?: 25000,
            bufferingTimeoutMs = p[intPreferencesKey("playback.bufferingTimeoutMs")] ?: 60000,
            bufferMs = p[intPreferencesKey("playback.bufferMs")] ?: 50000,
            startBufferMs = p[intPreferencesKey("playback.startBufferMs")] ?: 2500,
            resumeBufferMs = p[intPreferencesKey("playback.resumeBufferMs")] ?: 5000,
        ).normalized(),
        theme = enumOr(p[stringPreferencesKey("theme")], ThemeMode.DARK),
        accent = enumOr(p[stringPreferencesKey("accent")], Accent.LAVENDER),
        textSize = (p[intPreferencesKey("textSize")] ?: 1).coerceIn(0, 2),
        density = (p[intPreferencesKey("density")] ?: 1).coerceIn(0, 2),
        logos = p[booleanPreferencesKey("logos")] ?: true,
        preview = p[booleanPreferencesKey("preview")] ?: true,
        startup = enumOr(p[stringPreferencesKey("startup")], StartupMode.GUIDE),
        selectedPlaylistId = p[stringPreferencesKey("playlist")], lastChannelId = p[stringPreferencesKey("lastChannel")],
        lastCategory = p[stringPreferencesKey("lastCategory")] ?: "Все каналы",
        playlistRefreshHours = p[intPreferencesKey("playlistRefreshHours")]?.takeIf { it in RefreshIntervals.playlistHours } ?: 24,
        epgRefreshHours = p[intPreferencesKey("epgRefreshHours")]?.takeIf { it in RefreshIntervals.epgHours } ?: 6,
        playerControlsSeconds = p[intPreferencesKey("playerControlsSeconds")]?.takeIf { it in PlayerControlTimeouts.seconds } ?: 5,
        recentChannelIds = runCatching {
            val list = JSONArray(p[stringPreferencesKey("recentChannels")] ?: "[]")
            RecentChannels.normalize((0 until list.length()).map { list.optString(it) })
        }.getOrDefault(emptyList()),
        channelSelection = when (p[stringPreferencesKey("selection.kind")]) {
            "all" -> ChannelSelection.All
            "favourites" -> ChannelSelection.Favourites
            "recent" -> ChannelSelection.Recent
            "category" -> ChannelSelection.Category(p[stringPreferencesKey("selection.playlist")].orEmpty(), p[stringPreferencesKey("selection.category")].orEmpty())
            else -> ChannelSelection.legacy(p[stringPreferencesKey("lastCategory")] ?: "Все каналы", p[stringPreferencesKey("playlist")].orEmpty())
        })
    val onboarding = data.map(::readOnboarding)
    private fun readOnboarding(p: Preferences) = OnboardingState(
        complete = p[booleanPreferencesKey("on.complete")] ?: false,
        step = p[intPreferencesKey("on.step")] ?: 0,
        playlistId = p[stringPreferencesKey("on.playlist")],
        provider = ProviderProfile(
            key = p[stringPreferencesKey("on.provider.key")] ?: "",
            baseOrigin = p[stringPreferencesKey("on.provider.base")] ?: "https://iptv.online",
            playlistOrigin = p[stringPreferencesKey("on.provider.playlist")].orEmpty(),
            epgOrigin = p[stringPreferencesKey("on.provider.epg")].orEmpty(),
            logoOrigin = p[stringPreferencesKey("on.provider.logo")].orEmpty(),
            streamOrigin = p[stringPreferencesKey("on.provider.stream")].orEmpty(),
            epgVariant = enumOr(p[stringPreferencesKey("on.provider.variant")], EpgVariant.FULL_GZIP)))
    suspend fun settings(s: AppSettings) = changeSettings { s }
    suspend fun changeSettings(change: (AppSettings) -> AppSettings) { store.edit { p ->
        val s = change(readSettings(p))
        val network = s.playbackNetwork.normalized()
        p[intPreferencesKey("playback.reconnectAttempts")] = network.reconnectAttempts
        p[intPreferencesKey("playback.retryDelayMs")] = network.retryDelayMs
        p[intPreferencesKey("playback.connectTimeoutMs")] = network.connectTimeoutMs
        p[intPreferencesKey("playback.readTimeoutMs")] = network.readTimeoutMs
        p[intPreferencesKey("playback.bufferingTimeoutMs")] = network.bufferingTimeoutMs
        p[intPreferencesKey("playback.bufferMs")] = network.bufferMs
        p[intPreferencesKey("playback.startBufferMs")] = network.startBufferMs
        p[intPreferencesKey("playback.resumeBufferMs")] = network.resumeBufferMs

        p[stringPreferencesKey("theme")] = s.theme.name; p[stringPreferencesKey("accent")] = s.accent.name
        p[intPreferencesKey("textSize")] = s.textSize.coerceIn(0, 2); p[intPreferencesKey("density")] = s.density.coerceIn(0, 2)
        p[booleanPreferencesKey("logos")] = s.logos; p[booleanPreferencesKey("preview")] = s.preview
        p[stringPreferencesKey("startup")] = s.startup.name; p.setNullable("playlist", s.selectedPlaylistId)
        p.setNullable("lastChannel", s.lastChannelId); p[stringPreferencesKey("lastCategory")] = s.lastCategory
        p[intPreferencesKey("playlistRefreshHours")] = s.playlistRefreshHours.takeIf { it in RefreshIntervals.playlistHours } ?: 24
        p[intPreferencesKey("epgRefreshHours")] = s.epgRefreshHours.takeIf { it in RefreshIntervals.epgHours } ?: 6
        p[intPreferencesKey("playerControlsSeconds")] = s.playerControlsSeconds.takeIf { it in PlayerControlTimeouts.seconds } ?: 5
        p[stringPreferencesKey("recentChannels")] = JSONArray(RecentChannels.normalize(s.recentChannelIds)).toString()
        val selection = s.channelSelection ?: ChannelSelection.legacy(s.lastCategory, s.selectedPlaylistId.orEmpty())
        p[stringPreferencesKey("selection.kind")] = when (selection) {
            ChannelSelection.All -> "all"
            ChannelSelection.Favourites -> "favourites"
            ChannelSelection.Recent -> "recent"
            is ChannelSelection.Category -> "category"
        }
        p.setNullable("selection.playlist", (selection as? ChannelSelection.Category)?.playlistId)
        p.setNullable("selection.category", (selection as? ChannelSelection.Category)?.name)
    } }
    suspend fun onboarding(s: OnboardingState) = changeOnboarding { s }
    suspend fun changeOnboarding(change: (OnboardingState) -> OnboardingState) { store.edit { p ->
        val s = change(readOnboarding(p))
        listOf("on.name", "on.location", "on.kind", "on.epg", "on.epgKind", "on.ua", "on.referer").forEach { p.remove(stringPreferencesKey(it)) }
        p[booleanPreferencesKey("on.complete")] = s.complete; p[intPreferencesKey("on.step")] = s.step
        p.setNullable("on.playlist", s.playlistId)
        p[stringPreferencesKey("on.provider.key")] = s.provider.key
        p[stringPreferencesKey("on.provider.base")] = s.provider.baseOrigin
        p[stringPreferencesKey("on.provider.playlist")] = s.provider.playlistOrigin
        p[stringPreferencesKey("on.provider.epg")] = s.provider.epgOrigin
        p[stringPreferencesKey("on.provider.logo")] = s.provider.logoOrigin
        p[stringPreferencesKey("on.provider.stream")] = s.provider.streamOrigin
        p[stringPreferencesKey("on.provider.variant")] = s.provider.epgVariant.name
    } }
    private fun MutablePreferences.setNullable(name: String, value: String?) { val key = stringPreferencesKey(name); if (value == null) remove(key) else set(key, value) }
    private inline fun <reified T: Enum<T>> enumOr(value: String?, fallback: T): T = enumValues<T>().firstOrNull { it.name == value } ?: fallback
}
