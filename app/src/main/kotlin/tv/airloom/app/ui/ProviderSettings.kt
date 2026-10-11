package tv.airloom.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import tv.airloom.app.*
import tv.airloom.core.*

@Composable
internal fun ProviderSettings(state: UiState, actions: UiActions, epg: Boolean) {
    var offsetEditor by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    // Access keys deliberately do not enter saved-instance state.
    var original by remember { mutableStateOf(ProviderProfile()) }
    var draft by remember { mutableStateOf(ProviderProfile()) }
    var awaitingSave by remember { mutableStateOf(false) }
    var restore by remember { mutableIntStateOf(0) }
    val trigger = remember { FocusRequester() }
    fun close() { editor = false; advanced = false; discard = false; awaitingSave = false; draft = ProviderProfile(); original = ProviderProfile(); restore++ }
    fun dismiss() {
        if (state.busy) return
        if (advanced) advanced = false
        else if (draft != original) discard = true else close()
    }
    LaunchedEffect(restore) { if (restore > 0) { withFrameNanos { }; runCatching { trigger.requestFocus() } } }
    LaunchedEffect(state.providerSaveRevision) {
        if (awaitingSave) close()
    }
    SettingsPage(if (epg) "Телепрограмма" else "Подключение", if (epg) "Расписание, обновления и коррекция времени." else "Ваш доступ к IPTV.ONLINE.") {
        SettingRow(if (epg) "Источник телепрограммы" else "Профиль IPTV.ONLINE",
            if (epg) listOf("Полная .gz", "Полная .xml", "Лёгкая .gz", "Лёгкая .xml")[state.provider?.epgVariant?.ordinal ?: 0]
            else if (state.provider != null) "Профиль настроен · ключ скрыт" else "Настроить подключение",
            "OK — изменить", Modifier.focusRequester(trigger).testTag("provider_edit"), icon = if (epg) Icons.Outlined.CalendarMonth else Icons.Outlined.Key,
            enabled = !state.busy) { original = state.provider ?: ProviderProfile(); draft = original; editor = true }
        val intervals = if (epg) RefreshIntervals.epgHours else RefreshIntervals.playlistHours
        SettingChoice("Автообновление", "provider_interval", intervals.map { if (it == 0) "Выключено" else "Каждые $it ч" },
            intervals.indexOf(if (epg) state.settings.epgRefreshHours else state.settings.playlistRefreshHours)) { i ->
            actions.changeSettings { if (epg) it.copy(epgRefreshHours = intervals[i]) else it.copy(playlistRefreshHours = intervals[i]) }
        }
        if (epg) state.epgSources.firstOrNull()?.let { source ->
            SettingRow("Обновить телепрограмму", "${source.programmeCount} передач", updatedLabel(source.lastSuccess), enabled = !state.busy) { actions.refreshEpg(source.id) }
            source.lastError?.let { Label(it, color = Muted, size = 12) }
            SettingRow("Сдвиг времени", "${source.offsetMinutes} мин.", "OK — скорректировать расписание") { advanced = false; offsetEditor = true }
            if (offsetEditor) AirloomDialog("Сдвиг времени", { offsetEditor = false; restore++ }) {
                Label("Текущий сдвиг: ${source.offsetMinutes} мин.")
                InitialFocus { TvAction("−30 минут", it, enabled = !state.busy) { actions.updateEpgOffset(source.id, source.offsetMinutes - 30) } }
                TvAction("+30 минут", enabled = !state.busy) { actions.updateEpgOffset(source.id, source.offsetMinutes + 30) }
                TvAction("Без сдвига", enabled = !state.busy) { actions.updateEpgOffset(source.id, 0) }
            }
        } else state.playlists.firstOrNull()?.let { source ->
            SettingRow("Обновить список каналов", "${source.channelCount} каналов", updatedLabel(source.lastSuccess), enabled = !state.busy) { actions.refreshPlaylist(source.id) }
            source.lastError?.let { Label(it, color = Muted, size = 12) }
        }
        if (state.busy && !editor) { ProgressPanel(state.progress); TvAction("Отменить", onClick = actions::cancelImport) }
        state.error?.let { Label(it, color = Muted, size = 12) }
    }
    if (editor) AirloomDialog(if (advanced) "Дополнительные домены" else if (epg) "Источник телепрограммы" else "Изменить подключение", ::dismiss, animateDismiss = false, dismissEnabled = !state.busy) {
        if (advanced) {
            ProviderDomainPicker("settings_playlist", "Домен плейлиста", draft.playlistOrigin, { draft = draft.copy(playlistOrigin = it) }, inheritedLabel = "Основной домен", enabled = !state.busy)
            ProviderDomainPicker("settings_logo", "Домен логотипов", draft.logoOrigin, { draft = draft.copy(logoOrigin = it) }, inheritedLabel = "Основной домен", enabled = !state.busy)
            ProviderDomainPicker("settings_stream", "Домен потоков", draft.streamOrigin, { draft = draft.copy(streamOrigin = it) }, inheritedLabel = "Как в плейлисте", enabled = !state.busy)
            TvAction("К профилю", enabled = !state.busy) { advanced = false }
        } else {
            Label("Рабочие данные сохранятся, если проверка не удастся.", size = 12, color = Muted)
            if (epg) {
                SettingChoice("Вариант EPG", "settings_epg_variant", listOf("Полная .gz", "Полная .xml", "Лёгкая .gz", "Лёгкая .xml"), draft.epgVariant.ordinal, enabled = !state.busy) { draft = draft.copy(epgVariant = EpgVariant.entries[it]) }
                ProviderDomainPicker("settings_epg", "Домен EPG", draft.epgOrigin, { draft = draft.copy(epgOrigin = it) }, inheritedLabel = "Основной домен", enabled = !state.busy)
            } else {
                ProviderDomainPicker("settings_base", "Основной домен", draft.baseOrigin, { draft = draft.copy(baseOrigin = it) }, enabled = !state.busy)
                Entry("Ключ доступа", draft.key, { draft = draft.copy(key = it) }, private = true, enabled = !state.busy)
                SettingRow("Дополнительные домены", description = "Плейлист, логотипы и потоки", enabled = !state.busy) { advanced = true }
            }
            TvAction("Проверить и сохранить", Modifier.fillMaxWidth().testTag("provider_save"), enabled = !state.busy && draft.key.isNotBlank() && draft.baseOrigin.isNotBlank()) {
                awaitingSave = true
                if (epg) actions.saveProviderEpg(draft) else actions.saveProvider(draft)
            }
            TvAction("Отмена", enabled = !state.busy, onClick = ::dismiss)
        }
        if (state.busy) { ProgressPanel(state.progress); TvAction("Отменить операцию") { awaitingSave = false; actions.cancelImport() } }
        state.error?.let { Label(it, color = Muted, size = 12) }
    }
    if (discard) AirloomDialog("Есть несохранённые изменения", { discard = false }) {
        InitialFocus { TvAction("Продолжить редактирование", it) { discard = false } }
        TvAction("Отбросить изменения", Modifier.testTag("provider_discard"), onClick = ::close)
    }
}

private fun updatedLabel(time: Long) = if (time == 0L) "Ещё не обновлялось" else "Обновлено ${dayLabel(time)} в ${timeLabel(time)}"
