package tv.airloom.core

enum class SourceKind { URL, FILE }
data class PlaylistSource(val id: String, val name: String, val location: String, val kind: SourceKind = SourceKind.URL, val userAgent: String = "Airloom/0.1", val referer: String = "", val lastSuccess: Long = 0, val lastError: String? = null, val channelCount: Int = 0)
data class CatchupSpec(val mode: String = "", val source: String = "", val days: Int = 0, val correctionSeconds: Long = 0)
data class Channel(val id: String, val playlistId: String, val name: String, val url: String, val group: String = "Без категории", val tvgId: String = "", val tvgName: String = "", val logo: String = "", val number: Int = 0, val catchup: CatchupSpec = CatchupSpec(), val userAgent: String = "", val referer: String = "", val favourite: Boolean = false, val hidden: Boolean = false, val order: Int = 0, val favouriteOrder: Int = 0, val epgSourceId: String? = null, val epgChannelId: String? = null, val epgLogo: String = "")
data class ChannelPreferences(val channelId: String, val favourite: Boolean = false, val hidden: Boolean = false, val order: Int = 0, val favouriteOrder: Int = 0)
data class Category(val playlistId: String, val name: String, val count: Int, val hidden: Boolean = false, val order: Int = 0, val pinned: Boolean = false, val iconOverride: String? = null)
data class EpgSource(val id: String, val playlistId: String, val location: String, val kind: SourceKind = SourceKind.URL, val offsetMinutes: Int = 0, val lastSuccess: Long = 0, val lastError: String? = null, val programmeCount: Int = 0)
enum class EpgVariant(val fileName: String, val hasDescriptions: Boolean) {
    FULL_GZIP("epg.xml.gz", true), FULL_XML("epg.xml", true),
    LITE_GZIP("epg-lite.xml.gz", false), LITE_XML("epg-lite.xml", false)
}
/** One IPTV.ONLINE account. Blank service origins inherit [baseOrigin]; streams use M3U URLs by default. */
data class ProviderProfile(
    val key: String = "",
    val baseOrigin: String = "https://iptv.online",
    val playlistOrigin: String = "",
    val epgOrigin: String = "",
    val logoOrigin: String = "",
    val streamOrigin: String = "",
    val epgVariant: EpgVariant = EpgVariant.FULL_GZIP,
)
data class EpgChannel(val sourceId: String, val id: String, val name: String, val icon: String = "")
data class EpgBinding(val channelId: String, val sourceId: String, val epgChannelId: String, val manual: Boolean = false)
data class Programme(val id: String, val sourceId: String, val channelId: String, val title: String, val start: Long, val end: Long, val description: String = "", val category: String = "", val catchupId: String = "")
data class ParsedPlaylist(val channels: List<Channel>, val epgUrls: List<String>, val warnings: Int = 0)
data class ImportProgress(val stage: String = "", val completed: Int = 0, val total: Int? = null, val committed: Boolean = false)
data class ImportResult(val sourceId: String, val channels: Int = 0, val categories: Int = 0, val archiveChannels: Int = 0, val epgUrls: List<String> = emptyList(), val programmes: Int = 0, val matchedChannels: Int = 0, val warnings: Int = 0)
enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class Accent { LAVENDER, TEAL, BLUE, PEACH }
enum class StartupMode { GUIDE, PREVIEW, FULLSCREEN }
enum class RefreshKind { PLAYLIST, EPG }
object RefreshIntervals {
    val playlistHours = listOf(0, 6, 12, 24, 48)
    val epgHours = listOf(0, 1, 3, 6, 12, 24)
}
object PlayerControlTimeouts { val seconds = listOf(0, 3, 5, 10, 15) }
data class AppSettings(val playbackNetwork: PlaybackNetworkSettings = PlaybackNetworkSettings(), val theme: ThemeMode = ThemeMode.DARK, val accent: Accent = Accent.LAVENDER, val textSize: Int = 1, val density: Int = 1, val logos: Boolean = true, val preview: Boolean = true, val startup: StartupMode = StartupMode.GUIDE, val selectedPlaylistId: String? = null, val lastChannelId: String? = null, val lastCategory: String = "Все каналы", val playlistRefreshHours: Int = 24, val epgRefreshHours: Int = 6, val playerControlsSeconds: Int = 5, val recentChannelIds: List<String> = emptyList(), val channelSelection: ChannelSelection? = ChannelSelection.legacy(lastCategory, selectedPlaylistId.orEmpty()))
data class OnboardingState(val complete: Boolean = false, val step: Int = 0, val playlistId: String? = null, val provider: ProviderProfile = ProviderProfile())
sealed interface PlaybackRequest {
    val channel: Channel
    data class Live(override val channel: Channel): PlaybackRequest
    data class Archive(override val channel: Channel, val programme: Programme): PlaybackRequest
}
data class ResolvedStream(val url: String, val headers: Map<String, String> = emptyMap(), val archive: Boolean = false)
sealed interface CatchupResult {
    data class Available(val stream: ResolvedStream): CatchupResult
    data class Unavailable(val reason: String): CatchupResult
}
