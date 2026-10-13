package tv.airloom.app.ui

import androidx.compose.ui.res.stringResource
import tv.airloom.app.R

import androidx.compose.foundation.background
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Formatters are immutable and thread-safe; building one per label was a per-row cost on every recomposition.
internal val RussianLocale = Locale("ru")
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("d MMMM, EEE", RussianLocale)
internal val dayMonthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", RussianLocale)
internal val dayMonthWeekdayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM · EEE", RussianLocale)
internal fun timeLabel(epoch: Long): String = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(timeFormat)
internal fun dayLabel(epoch: Long): String = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(dayFormat)

@Composable
internal fun AirloomDialog(title: String, onDismiss: () -> Unit, animateDismiss: Boolean = true, dismissEnabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val duration = motionDuration(160)
    LaunchedEffect(Unit) { visible = true }
    val close: () -> Unit = { if (visible && dismissEnabled) { if (!animateDismiss) onDismiss() else { visible = false; scope.launch { delay(duration.toLong()); onDismiss() } } } }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AnimatedVisibility(visible, enter = fadeIn(tween(duration)) + scaleIn(tween(duration, easing = Motion.Decelerate), initialScale = .96f),
            exit = fadeOut(tween(duration)) + scaleOut(tween(duration), targetScale = .96f)) {
        CompositionLocalProvider(LocalUiInteractive provides visible, LocalSettingsFocus provides null, LocalNavigationFocus provides null) {
        Column(Modifier.width(480.dp).heightIn(max = 460.dp).clip(PanelShape).background(LocalPalette.current.panel).padding(26.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label(title, size = 22, bold = true)
            content()
            TvAction(stringResource(R.string.ui_close), Modifier.align(Alignment.End), enabled = dismissEnabled, onClick = close)
        }
        }
        }
    }
}

@Composable
internal fun EmptyState(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Label(stringResource(R.string.ui_empty_symbol), size = 36, color = LocalAccent.current)
        Label(title, size = 22, bold = true)
        Label(description, size = 14, color = Muted)
    }
}

@Composable
internal fun InitialFocus(modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    val requester = remember { FocusRequester() }
    val interactive = LocalUiInteractive.current
    LaunchedEffect(interactive) { if (interactive) { withFrameNanos { }; runCatching { requester.requestFocus() } } }
    content(modifier.focusRequester(requester))
}
