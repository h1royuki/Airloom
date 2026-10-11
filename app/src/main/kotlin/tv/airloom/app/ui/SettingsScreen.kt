package tv.airloom.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.launch
import tv.airloom.app.*
import tv.airloom.core.*

private val SettingsSection.title: String get() = when (this) {
    SettingsSection.CONNECTION -> "Подключение"
    SettingsSection.EPG -> "Телепрограмма"
    SettingsSection.APPEARANCE -> "Изображение и интерфейс"
    SettingsSection.PLAYBACK -> "Воспроизведение"
    SettingsSection.NETWORK -> "Сеть и буфер"
    SettingsSection.LISTS -> "Каналы и категории"
    SettingsSection.ABOUT -> "О приложении"
}
private val SettingsSection.icon get() = when (this) {
    SettingsSection.CONNECTION -> Icons.Outlined.Link
    SettingsSection.EPG -> Icons.Outlined.CalendarMonth
    SettingsSection.APPEARANCE -> Icons.Outlined.Palette
    SettingsSection.PLAYBACK -> Icons.Outlined.PlayCircle
    SettingsSection.NETWORK -> Icons.Outlined.Wifi
    SettingsSection.LISTS -> Icons.Outlined.FormatListBulleted
    SettingsSection.ABOUT -> Icons.Outlined.Info
}

@Composable
internal fun SettingsScreen(state: UiState, actions: UiActions) {
    var section by rememberSaveable(state.settingsSection) { mutableStateOf(state.settingsSection) }
    var contentFocused by remember { mutableStateOf(false) }
    val menu = remember { SettingsSection.entries.associateWith { FocusRequester() } }
    val content = remember { FocusRequester() }
    val returns = remember { SettingsSection.entries.associateWith { SettingsFocus() } }
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    fun enter(next: SettingsSection) {
        section = next
        scope.launch { withFrameNanos { }; withFrameNanos { }
            if (runCatching { returns.getValue(next).last?.requestFocus() }.getOrNull() == null)
                runCatching { content.requestFocus() }
        }
    }
    BackHandler(contentFocused) { menu.getValue(section).requestFocus() }
    LaunchedEffect(Unit) { withFrameNanos { }; menu.getValue(section).requestFocus() }
    NavigationLayout(state, actions) {
        Column(Modifier.width(if (LocalTextScale.current > 1f) 296.dp else 272.dp).fillMaxHeight()
            .padding(start = 16.dp, top = SafeVertical, bottom = SafeVertical).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label("Настройки", Modifier.padding(start = 12.dp, bottom = 12.dp), size = 22, bold = true)
            SettingsSection.entries.forEach { item ->
                TvAction(item.title, Modifier.fillMaxWidth().focusRequester(menu.getValue(item)).testTag("settings_section_${item.name}")
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.DirectionRight) { if (event.type == KeyEventType.KeyDown) enter(item); true } else false
                    }, selected = section == item, icon = item.icon, maxLines = 3, quiet = true) { enter(item) }
            }
        }
        CompositionLocalProvider(LocalSettingsFocus provides returns.getValue(section)) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp, end = SafeHorizontal, top = SafeVertical, bottom = SafeVertical).focusRequester(content)
                .onFocusChanged { contentFocused = it.hasFocus }.focusGroup()
                .onKeyEvent { event ->
                    if (event.key == Key.DirectionLeft && event.type == KeyEventType.KeyDown) {
                        menu.getValue(section).requestFocus(); true
                    } else false
                }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    holder.SaveableStateProvider(section.name) {
                        when (section) {
                            SettingsSection.CONNECTION -> ProviderSettings(state, actions, epg = false)
                            SettingsSection.EPG -> ProviderSettings(state, actions, epg = true)
                            SettingsSection.APPEARANCE -> AppearanceSettings(state, actions)
                            SettingsSection.PLAYBACK -> PlaybackSettings(state, actions)
                            SettingsSection.NETWORK -> NetworkSettings(state.settings.playbackNetwork, actions)
                            SettingsSection.LISTS -> ListManager(state, actions)
                            SettingsSection.ABOUT -> SettingsPage("О приложении", "Airloom · Android TV") {
                                Label("Версия ${BuildConfig.VERSION_NAME}", size = 16, bold = true)
                                Label("Один профиль IPTV.ONLINE. Каналы, телепрограмма и архив.", color = Muted)
                                Label("↑ ↓ — выбор · OK — открыть · Назад — вернуться", color = Muted)
                            }
                        }
                    }
                }
                if (state.settingsSaving) Label("Сохранение…", size = 12, color = Muted)
            }
        }
    }
}

@Composable
private fun AppearanceSettings(state: UiState, actions: UiActions) = SettingsPage("Изображение и интерфейс", "Изменения сохраняются сразу.") {
    val s = state.settings
    SettingChips("Тема", "settings_theme", listOf("Тёмная", "Светлая", "Системная"), s.theme.ordinal) { i -> actions.changeSettings { it.copy(theme = ThemeMode.entries[i]) } }
    SettingChips("Акцент", "settings_accent", listOf("Лаванда", "Мята", "Небесный", "Персик"), s.accent.ordinal, listOf(Lavender, Mint, Sky, Peach)) { i -> actions.changeSettings { it.copy(accent = Accent.entries[i]) } }
    SettingChips("Размер текста", "settings_text", listOf("Маленький", "Обычный", "Крупный"), s.textSize) { i -> actions.changeSettings { it.copy(textSize = i) } }
    SettingChips("Плотность списка", "settings_density", listOf("Компактная", "Обычная", "Просторная"), s.density) { i -> actions.changeSettings { it.copy(density = i) } }
    SettingSwitch("Логотипы каналов", s.logos, Modifier.testTag("settings_logos"), icon = Icons.Outlined.Image) { actions.changeSettings { it.copy(logos = !it.logos) } }
    SettingSwitch("Предпросмотр канала", s.preview, Modifier.testTag("settings_preview"), "Один кадр без звука на главном экране", Icons.Outlined.Preview) { actions.changeSettings { it.copy(preview = !it.preview) } }
}

@Composable
private fun PlaybackSettings(state: UiState, actions: UiActions) = SettingsPage("Воспроизведение", "Просмотр начинается только после явного открытия канала.") {
    SettingChips("При запуске", "settings_startup", listOf("Список каналов", "Последний канал без автозапуска"), if (state.settings.startup == StartupMode.FULLSCREEN) 1 else 0) { i ->
        actions.changeSettings { it.copy(startup = if (i == 1) StartupMode.FULLSCREEN else StartupMode.GUIDE) }
    }
    SettingChoice("Скрывать панель плеера через", "settings_controls", PlayerControlTimeouts.seconds.map { if (it == 0) "Не скрывать" else "$it с" }, PlayerControlTimeouts.seconds.indexOf(state.settings.playerControlsSeconds)) { i ->
        actions.changeSettings { it.copy(playerControlsSeconds = PlayerControlTimeouts.seconds[i]) }
    }
}
