package tv.airloom.app.ui

import androidx.compose.ui.res.stringResource
import tv.airloom.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import tv.airloom.core.*

internal data class WizardCallbacks(
    val change: (OnboardingState) -> Unit,
    val importPlaylist: () -> Unit,
    val importEpg: () -> Unit,
    val skipEpg: () -> Unit,
    val cancel: () -> Unit,
    val settings: (AppSettings) -> Unit,
    val finish: () -> Unit,
    val testPlayback: () -> Unit,
    val exit: () -> Unit,
)

@Composable
internal fun OnboardingScreen(state: OnboardingState, settings: AppSettings, result: ImportResult?, progress: ImportProgress?, error: String?, busy: Boolean, callbacks: WizardCallbacks, adding: Boolean = false, saving: Boolean = false) {
    val stages = listOf("Приветствие", "Провайдер", "Каналы", "Телепрограмма", "Интерфейс", "Готово")
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.width(248.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(start = SafeHorizontal, end = 24.dp, top = 32.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(21.dp)) {
            Label(stringResource(R.string.ui_wizard_brand), size = 27, bold = true)
            Label(stringResource(R.string.ui_wizard_tagline), size = 14, color = Muted)
            Spacer(Modifier.height(14.dp))
            stages.forEachIndexed { index, title ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(50)).background(if (index == state.step) LocalPalette.current.accentContainer else LocalPalette.current.container), contentAlignment = Alignment.Center) { Label(if (index < state.step) "✓" else "${index + 1}", size = 12, color = if (index == state.step) LocalPalette.current.onAccentContainer else Muted) }
                    Label(title, size = 13, color = if (index == state.step) MaterialTheme.colorScheme.onSurface else Muted, bold = index == state.step)
                }
            }
            Spacer(Modifier.weight(1f))
            Label(stringResource(R.string.ui_wizard_platform), size = 10, color = Muted)
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 40.dp, end = SafeHorizontal, top = 32.dp, bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(17.dp)) {
            Label("НАСТРОЙКА  ·  ${state.step + 1} ИЗ 6", size = 11, color = LocalAccent.current, bold = true)
            when (state.step) {
                0 -> {
                    Spacer(Modifier.height(25.dp))
                    SectionTitle(stringResource(R.string.ui_wizard_welcome), "Каналы, телепрограмма и любимые передачи — в одном месте.")
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(22.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                        FeatureLine(stringResource(R.string.ui_wizard_channels_feature), "Выбирайте передачи и возвращайтесь к доступному архиву.")
                        FeatureLine(stringResource(R.string.ui_wizard_order_feature), "Соберите избранное и настройте порядок каналов.")
                        FeatureLine(stringResource(R.string.ui_wizard_remote_feature), "Для всех действий достаточно стрелок, OK и Назад.")
                    }
                    Label(stringResource(R.string.ui_wizard_provider_hint), size = 12, color = Muted)
                    InitialFocus { TvAction(stringResource(R.string.ui_wizard_start), it, icon = Icons.Outlined.ArrowForward) { callbacks.change(state.copy(step = 1)) } }
                }
                1 -> {
                    SectionTitle("Подключение IPTV.ONLINE", "Выберите основной домен и введите ключ или ссылку /play/…/m3u8.")
                    InitialFocus { ProviderDomainPicker("base", "Основной домен", state.provider.baseOrigin,
                        { callbacks.change(state.copy(provider = state.provider.copy(baseOrigin = it))) }, modifier = it, enabled = !busy) }
                    Entry("Ключ доступа", state.provider.key, { callbacks.change(state.copy(provider = state.provider.copy(key = it))) }, private = true)
                    var advanced by remember { mutableStateOf(false) }
                    TvAction(if (advanced) "Скрыть отдельные домены" else "Отдельные домены") { advanced = !advanced }
                    if (advanced) {
                        ProviderDomainPicker("playlist", "Домен плейлиста", state.provider.playlistOrigin,
                            { callbacks.change(state.copy(provider = state.provider.copy(playlistOrigin = it))) }, inheritedLabel = "Основной домен", enabled = !busy)
                        ProviderDomainPicker("logo", "Домен логотипов", state.provider.logoOrigin,
                            { callbacks.change(state.copy(provider = state.provider.copy(logoOrigin = it))) }, inheritedLabel = "Основной домен", enabled = !busy)
                        ProviderDomainPicker("stream", "Домен потоков", state.provider.streamOrigin,
                            { callbacks.change(state.copy(provider = state.provider.copy(streamOrigin = it))) }, inheritedLabel = "Как в плейлисте", enabled = !busy)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvAction(stringResource(R.string.ui_back)) { callbacks.change(state.copy(step = 0)) }
                        TvAction("Проверить и загрузить каналы", enabled = state.provider.key.isNotBlank() && state.provider.baseOrigin.isNotBlank() && !busy, icon = Icons.Outlined.ArrowForward) { callbacks.importPlaylist() }
                    }
                }
                2 -> {
                    SectionTitle(if (busy) "Знакомимся с вашим плейлистом" else if (error != null) "Не удалось импортировать" else "Плейлист готов", "Видеопотоки не запускаются во время проверки.")
                    if (busy) ProgressPanel(progress)
                    else if (error == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatTile("${result?.channels ?: 0}", "каналов")
                            StatTile("${result?.categories ?: 0}", "категорий")
                            StatTile("${result?.archiveChannels ?: 0}", "с архивом")
                        }
                        Label(stringResource(R.string.ui_wizard_archive_hint), size = 12, color = Muted)
                        if ((result?.warnings ?: 0) > 0) Label("Пропущено повреждённых записей: ${result?.warnings}", color = Peach, size = 12)
                        TvAction(stringResource(R.string.ui_wizard_test_playback), icon = Icons.Outlined.PlayArrow, onClick = callbacks.testPlayback)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (busy) InitialFocus { TvAction(stringResource(R.string.ui_cancel), it, onClick = callbacks.cancel) }
                        else { TvAction(stringResource(R.string.ui_correct)) { callbacks.change(state.copy(step = 1)) }; if (error != null) TvAction(stringResource(R.string.ui_retry), onClick = callbacks.importPlaylist) else InitialFocus { TvAction(stringResource(R.string.ui_next), it) { callbacks.change(state.copy(step = 3)) } } }
                    }
                }
                3 -> {
                    SectionTitle("Телепрограмма IPTV.ONLINE", "Полная версия содержит описания передач. Облегчённая загружается быстрее.")
                    ChoiceRow(listOf("Полная .gz", "Полная .xml", "Лёгкая .gz", "Лёгкая .xml"), state.provider.epgVariant.ordinal) {
                        callbacks.change(state.copy(provider = state.provider.copy(epgVariant = EpgVariant.entries[it])))
                    }
                    ProviderDomainPicker("epg", "Домен EPG", state.provider.epgOrigin,
                        { callbacks.change(state.copy(provider = state.provider.copy(epgOrigin = it))) }, inheritedLabel = "Основной домен", enabled = !busy)
                    Label("Если основной домен не отдаёт EPG, укажите здесь iptv.online.", size = 11, color = Muted)
                    val loaded = (result?.programmes ?: 0) > 0
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // The step opens on its own action; skipping it is a deliberate move away.
                        if (!busy && !loaded) InitialFocus { TvAction("Загрузить EPG", it, onClick = callbacks.importEpg) }
                        else TvAction("Загрузить EPG", enabled = !busy, onClick = callbacks.importEpg)
                    }
                    if (busy) ProgressPanel(progress)
                    if (!busy && loaded) Label("Загружено ${result?.programmes} передач · сопоставлено ${result?.matchedChannels} каналов", color = LocalAccent.current, size = 14)
                    Label(stringResource(R.string.ui_wizard_skip_epg_hint), size = 12, color = Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvAction(stringResource(R.string.ui_back), enabled = !busy) { callbacks.change(state.copy(step = 2)) }
                        if (busy) InitialFocus { TvAction(stringResource(R.string.ui_cancel), it, onClick = callbacks.cancel) }
                        else if (loaded) InitialFocus { TvAction("Далее", it) { callbacks.change(state.copy(step = 4)) } }
                        else TvAction("Пропустить", onClick = callbacks.skipEpg)
                    }
                }
                4 -> {
                    SectionTitle(stringResource(R.string.ui_wizard_appearance), "Настройте читаемость с вашего места перед телевизором.")
                    Label(stringResource(R.string.ui_text_size), size = 13, color = Muted)
                    ChoiceRow(listOf(stringResource(R.string.ui_text_small), stringResource(R.string.ui_text_normal), stringResource(R.string.ui_text_large)), settings.textSize) { callbacks.settings(settings.copy(textSize = it)) }
                    Label(stringResource(R.string.ui_wizard_density), size = 13, color = Muted)
                    ChoiceRow(listOf(stringResource(R.string.ui_density_compact), stringResource(R.string.ui_density_normal), stringResource(R.string.ui_density_spacious)), settings.density) { callbacks.settings(settings.copy(density = it)) }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Label(stringResource(R.string.ui_wizard_preview), size = 10, color = Muted)
                        Row(Modifier.fillMaxWidth().height(listOf(40, 48, 58)[settings.density.coerceIn(0,2)].dp).clip(RowShape).background(LocalPalette.current.focus).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { Label(stringResource(R.string.ui_wizard_sample_title), color = LocalPalette.current.onFocus, bold = true); Spacer(Modifier.weight(1f)); Label(stringResource(R.string.ui_wizard_sample_time), size = 12, color = LocalPalette.current.onFocus) }
                        Label(stringResource(R.string.ui_wizard_sample_description), size = 13, color = Muted)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { TvAction(stringResource(R.string.ui_back)) { callbacks.change(state.copy(step = 3)) }; TvAction(stringResource(R.string.ui_next)) { callbacks.change(state.copy(step = 5)) } }
                }
                else -> {
                    Spacer(Modifier.height(20.dp))
                    Label(stringResource(R.string.ui_wizard_checkmark), size = 54, color = LocalAccent.current)
                    SectionTitle(stringResource(R.string.ui_wizard_complete), "Настройка завершена. Всё остальное можно изменить в любой момент.")
                    Label("IPTV.ONLINE", size = 19, bold = true)
                    Label(stringResource(R.string.ui_remote_controls_hint), size = 13, color = Muted)
                    Label(stringResource(R.string.ui_wizard_explicit_play_hint), size = 12, color = Muted)
                    InitialFocus { TvAction(stringResource(R.string.ui_open_channels), it, icon = Icons.Outlined.LiveTv, onClick = callbacks.finish) }
                }
            }
            if (delayedLoading(saving)) LoadingIndicator(Modifier.size(18.dp), label = stringResource(R.string.ui_saving))
            if (error != null) Label(error, color = Peach, size = 13)
            if (adding) TvAction(stringResource(R.string.ui_return_settings), onClick = callbacks.exit)
        }
    }
}

@Composable private fun FeatureLine(title: String, text: String) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Label(title, size = 16, bold = true); Label(text, size = 12, color = Muted) } }
@Composable private fun StatTile(value: String, title: String) { Column(Modifier.width(130.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(17.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Label(value, size = 29, color = LocalAccent.current, bold = true); Label(title, size = 12, color = Muted) } }
@Composable internal fun ChoiceRow(options: List<String>, selected: Int, onChoose: (Int) -> Unit) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { options.forEachIndexed { i, text -> TvAction(text, selected = selected == i) { onChoose(i) } } } }
@Composable
internal fun ProgressPanel(progress: ImportProgress?) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LoadingIndicator(Modifier.size(24.dp))
            Label(progress?.stage?.ifBlank { "Подключение к источнику…" } ?: "Подключение к источнику…", size = 16, bold = true)
        }
        Label(if (progress?.total != null) "${progress.completed} / ${progress.total}" else "Обработано: ${progress?.completed ?: 0}", size = 12, color = Muted)
    }
}
