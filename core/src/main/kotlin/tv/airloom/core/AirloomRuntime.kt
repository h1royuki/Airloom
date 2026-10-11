package tv.airloom.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.IOException

/** Injectable time and execution contexts for catalogue, search and UI tests. */
class AirloomRuntime(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val computation: CoroutineDispatcher = Dispatchers.Default,
    val now: () -> Long = System::currentTimeMillis,
)

enum class FailureKind { NETWORK, ACCESS, NOT_FOUND, PLAYLIST, EPG, STORAGE, INVALID_PROFILE, OPERATION }
class AirloomFailure(val kind: FailureKind) : IOException(kind.name)

fun FailureKind.userMessage(): String = when (this) {
    FailureKind.NETWORK -> "Не удалось подключиться к источнику. Проверьте сеть и повторите."
    FailureKind.ACCESS -> "Источник отказал в доступе. Проверьте ключ и параметры подключения."
    FailureKind.NOT_FOUND -> "Источник не найден. Проверьте настройки подключения."
    FailureKind.PLAYLIST -> "Не удалось обработать плейлист. Проверьте источник и формат M3U."
    FailureKind.EPG -> "Не удалось обработать EPG. Проверьте источник и формат XMLTV."
    FailureKind.STORAGE -> "Не удалось сохранить изменения. Повторите попытку."
    FailureKind.INVALID_PROFILE -> "Проверьте ключ IPTV.ONLINE и указанные домены."
    FailureKind.OPERATION -> "Не удалось выполнить операцию. Повторите попытку."
}

/** Only changed fields are applied to the latest persisted value. */
fun AppSettings.patch(previous: AppSettings): (AppSettings) -> AppSettings = { current ->
    current.copy(
        playbackNetwork = playbackNetwork.patch(previous.playbackNetwork, current.playbackNetwork),
        theme = if (theme != previous.theme) theme else current.theme,
        accent = if (accent != previous.accent) accent else current.accent,
        textSize = if (textSize != previous.textSize) textSize else current.textSize,
        density = if (density != previous.density) density else current.density,
        logos = if (logos != previous.logos) logos else current.logos,
        preview = if (preview != previous.preview) preview else current.preview,
        startup = if (startup != previous.startup) startup else current.startup,
        selectedPlaylistId = if (selectedPlaylistId != previous.selectedPlaylistId) selectedPlaylistId else current.selectedPlaylistId,
        lastChannelId = if (lastChannelId != previous.lastChannelId) lastChannelId else current.lastChannelId,
        lastCategory = if (lastCategory != previous.lastCategory) lastCategory else current.lastCategory,
        playlistRefreshHours = if (playlistRefreshHours != previous.playlistRefreshHours) playlistRefreshHours else current.playlistRefreshHours,
        epgRefreshHours = if (epgRefreshHours != previous.epgRefreshHours) epgRefreshHours else current.epgRefreshHours,
        playerControlsSeconds = if (playerControlsSeconds != previous.playerControlsSeconds) playerControlsSeconds else current.playerControlsSeconds,
        recentChannelIds = if (recentChannelIds != previous.recentChannelIds) recentChannelIds else current.recentChannelIds,
        channelSelection = if (channelSelection != previous.channelSelection) channelSelection else current.channelSelection,
    )
}
