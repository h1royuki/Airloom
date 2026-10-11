package tv.airloom.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** TV calendar: disabled dates never take focus; arrows keep calendar geometry. */
@Composable
internal fun ProgrammeDatePicker(selected: LocalDate, earliest: LocalDate, today: LocalDate, channelName: String,
    onSelect: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(selected.coerceIn(earliest, today)).toString()) }
    val month = YearMonth.parse(monthText).coerceIn(YearMonth.from(earliest), YearMonth.from(today))
    val first = month.atDay(1)
    val offset = first.dayOfWeek.value - 1
    val requesters = remember(month) { (1..month.lengthOfMonth()).associateWith { FocusRequester() } }
    val entry = selected.coerceIn(earliest, today).takeIf { YearMonth.from(it) == month } ?: first.coerceIn(earliest, today)
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        val availableHeight = maxHeight
        Column(Modifier.width(440.dp).heightIn(max = availableHeight).clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(20.dp)
            .testTag("programme_calendar_dialog"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("Дата передачи", size = 20, bold = true)
        val interactive = LocalUiInteractive.current
        LaunchedEffect(interactive, month, earliest, today) {
            if (interactive) { withFrameNanos { }; requesters[entry.dayOfMonth]?.requestFocus() }
        }
        Label(channelName, size = 13, color = Muted, maxLines = 1)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvAction("Предыдущий месяц", Modifier.size(40.dp).testTag("programme_month_previous"), icon = androidx.compose.material.icons.Icons.Outlined.ChevronLeft,
                iconOnly = true, enabled = month > YearMonth.from(earliest)) { monthText = month.minusMonths(1).toString() }
            Box(Modifier.weight(1f), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Label(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru"))).replaceFirstChar { it.titlecase(Locale("ru")) }, size = 18, maxLines = 1)
            }
            TvAction("Следующий месяц", Modifier.size(40.dp).testTag("programme_month_next"), icon = androidx.compose.material.icons.Icons.Outlined.ChevronRight,
                iconOnly = true, enabled = month < YearMonth.from(today)) { monthText = month.plusMonths(1).toString() }
        }
        Row(Modifier.fillMaxWidth()) { listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach {
            Box(Modifier.weight(1f), contentAlignment = androidx.compose.ui.Alignment.Center) { Label(it, size = 12, color = Muted) }
        } }
        Column(Modifier.testTag("programme_calendar"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat((offset + month.lengthOfMonth() + 6) / 7) { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(7) { day ->
                        val number = week * 7 + day - offset + 1
                        if (number !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f))
                        else {
                            val date = month.atDay(number)
                            ProgrammeCalendarDay(number.toString(), Modifier.weight(1f).focusRequester(requesters.getValue(number))
                                .testTag("programme_date:$date").semantics { contentDescription = date.format(DateTimeFormatter.ofPattern("d MMMM yyyy, EEEE", Locale("ru"))) }.onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) false
                                    else {
                                        val delta = when (event.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; Key.DirectionUp -> -7; Key.DirectionDown -> 7; else -> 0 }
                                        val target = date.plusDays(delta.toLong())
                                        if (delta == 0) false
                                        else if (target in earliest..today && YearMonth.from(target) == month) { requesters[target.dayOfMonth]?.requestFocus(); true }
                                        else if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) true else false
                                    }
                                }, selected = date == selected, today = date == today, enabled = date in earliest..today) { onSelect(date) }
                        }
                    }
                }
            }
        }
        val format = dayMonthFormat
        Label("Архив заявлен: ${earliest.format(format)} — ${today.format(format)}", size = 12, color = Muted, maxLines = 1)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TvAction("К текущей", Modifier.testTag("programme_calendar_today")) { onSelect(today) }
            TvAction("Отмена", Modifier.testTag("programme_calendar_cancel"), onClick = onDismiss)
        }
        }
      }
    }
}


@Composable
private fun ProgrammeCalendarDay(label: String, modifier: Modifier, selected: Boolean, today: Boolean, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    val foreground = when { focused -> palette.onFocus; !enabled -> Muted.copy(alpha = .45f); selected -> palette.onAccentContainer; else -> palette.on }
    Box(modifier.height(34.dp)
        .focusHighlight(focused, if (selected) palette.accentContainer else Color.Transparent, RoundedCornerShape(10.dp))
        .onFocusChanged { focused = it.isFocused }.semantics { this.selected = selected }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Label(label, size = 13, color = foreground, maxLines = 1)
        if (today) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(3.dp)
            .clip(RoundedCornerShape(50)).background(foreground))
    }
}
