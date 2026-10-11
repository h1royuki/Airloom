package tv.airloom.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import tv.airloom.app.UiActions
import tv.airloom.core.PlaybackNetworkSettings

@Composable
internal fun NetworkSettings(settings: PlaybackNetworkSettings, actions: UiActions) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    val advancedTrigger = remember { FocusRequester() }
    var restoreAdvanced by remember { mutableIntStateOf(0) }
    fun closeAdvanced() { advanced = false; restoreAdvanced++ }
    LaunchedEffect(restoreAdvanced) { if (restoreAdvanced > 0) { withFrameNanos { }; runCatching { advancedTrigger.requestFocus() } } }
    androidx.activity.compose.BackHandler(advanced) { closeAdvanced() }
    fun change(update: (PlaybackNetworkSettings) -> PlaybackNetworkSettings) {
        actions.changeSettings { it.copy(playbackNetwork = update(it.playbackNetwork).normalized()) }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).testTag("network_settings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Сеть и буфер", "Сохраняется автоматически. Применяется при следующем открытии канала или ручном повторе.")
        if (!advanced) {
        NetworkChoice("Профиль буферизации", "network_profile", listOf("Быстрый", "Сбалансированный", "Нестабильная сеть"), settings.profile,
            fallback = "Свой") { index -> change { it.withProfile(index) } }
        Label("Больший запас помогает пережить краткие перебои. Фактический буфер зависит от потока и памяти; больший запас перед стартом увеличивает ожидание.", size = 12, color = Muted)
        NetworkChoice("Переподключения после ошибки", "network_attempts", (0..10).map { if (it == 0) "Выкл." else "$it" }, settings.reconnectAttempts) { value -> change { it.copy(reconnectAttempts = value) } }
        Label("Полные переподключения после внутренних повторов загрузки (до трёх). Задержка растёт до 15 секунд. Счётчик сбрасывается после минуты стабильного просмотра.", size = 12, color = Muted)
        TvAction("Дополнительные параметры", Modifier.focusRequester(advancedTrigger).testTag("network_advanced")) { advanced = true }
        }
        if (advanced) {
            InitialFocus { TvAction("К настройкам сети", it) { closeAdvanced() } }
            NetworkDuration("Начальная задержка повторов", "retry_delay", PlaybackNetworkSettings.retryDelays, settings.retryDelayMs) { v -> change { it.copy(retryDelayMs = v) } }
            NetworkDuration("Ожидание подключения", "connect_timeout", PlaybackNetworkSettings.connectTimeouts, settings.connectTimeoutMs) { v -> change { it.copy(connectTimeoutMs = v) } }
            NetworkDuration("Ожидание данных", "read_timeout", PlaybackNetworkSettings.readTimeouts, settings.readTimeoutMs) { v -> change { it.copy(readTimeoutMs = v) } }
            NetworkDuration("Максимальная непрерывная буферизация", "buffer_timeout", PlaybackNetworkSettings.bufferingTimeouts, settings.bufferingTimeoutMs) { v -> change { it.copy(bufferingTimeoutMs = v) } }
            NetworkDuration("Запас буфера", "buffer", PlaybackNetworkSettings.buffers, settings.bufferMs) { v -> change { it.copy(bufferMs = v) } }
            NetworkDuration("Буфер перед стартом", "start_buffer", PlaybackNetworkSettings.startBuffers, settings.startBufferMs) { v -> change { it.copy(startBufferMs = v) } }
            NetworkDuration("Буфер после остановки на загрузку", "resume_buffer", PlaybackNetworkSettings.resumeBuffers, settings.resumeBufferMs) { v -> change { it.copy(resumeBufferMs = v) } }
            Label("Целевой объём медиабуфера — до 64 МиБ. Это не ограничение всей памяти приложения. Настройки не меняют загрузку EPG, плейлиста или кадра предпросмотра.", size = 12, color = Muted)
        }
        TvAction("Сбросить настройки сети", Modifier.testTag("network_reset")) { change { PlaybackNetworkSettings() } }
    }
}

@Composable
private fun NetworkDuration(label: String, id: String, options: List<Int>, value: Int, choose: (Int) -> Unit) {
    NetworkChoice(label, "network_$id", options.map { if (it % 1000 == 0) "${it / 1000} с" else "2,5 с" }, options.indexOf(value)) { choose(options[it]) }
}

@Composable
private fun NetworkChoice(label: String, id: String, options: List<String>, selected: Int, fallback: String = "", choose: (Int) -> Unit) {
    SettingChoice(label, id, options, selected, choose = choose)
}
