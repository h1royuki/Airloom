package tv.airloom.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.tv.material3.MaterialTheme
import java.time.ZoneId
import tv.airloom.app.*
import tv.airloom.core.*

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ChannelProgrammePanel(channel: Channel, browser: ProgrammeBrowserState, now: Long, preview: Boolean,
    playlistLocation: String?, actions: UiActions,
    restoreContentFocus: Boolean, entryFocus: FocusRequester, onContentFocus: () -> Unit, returnToChannel: () -> Unit) {
    val interactive = LocalUiInteractive.current
    val focusManager = LocalFocusManager.current
    val today = ProgrammeBrowserPolicy.today(now)
    val date = browser.date ?: today
    val past = date < today
    val earliest = ProgrammeBrowserPolicy.earliest(channel, now)
    val archive = ProgrammeBrowserPolicy.hasArchive(channel)
    val rows = ProgrammeBrowserPolicy.visible(channel, browser.programmes, now)
    val selected = ProgrammeBrowserPolicy.select(rows, browser.selected, now, date, ZoneId.systemDefault())
    var calendar by rememberSaveable(channel.id) { mutableStateOf(false) }
    var target by rememberSaveable(channel.id) { mutableStateOf("date") }
    var hasFocus by remember { mutableStateOf(false) }
    var restoreDate by remember { mutableIntStateOf(0) }
    val dateFocus = entryFocus
    val selectedFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var restored by remember { mutableStateOf(false) }
    var previousSelection by remember { mutableStateOf<Programme?>(null) }
    var lastPositionKey by rememberSaveable(channel.id) { mutableStateOf("") }
    val key = "$date:${browser.positionRevision}"
    // Restore only on entry/explicit date changes, never on each focus move or clock tick.
    LaunchedEffect(interactive, browser.load.phase, rows.map { it.sourceId to it.id }, key) {
        if (!interactive) return@LaunchedEffect
        if (rows.isNotEmpty() && lastPositionKey != key) {
            val index = rows.indexOfFirst { ProgrammeBrowserPolicy.same(it, selected) }.coerceAtLeast(0)
            listState.scrollToItem(index)
            lastPositionKey = key
        }
        val removedFocusedRow = target == "programme" && restoreContentFocus && previousSelection != null && rows.none { ProgrammeBrowserPolicy.same(it, previousSelection) }
        previousSelection = selected
        if ((!restored && restoreContentFocus || removedFocusedRow) && browser.load.phase != LoadPhase.LOADING) {
            if (target == "programme" && selected != null) {
                val index = rows.indexOfFirst { ProgrammeBrowserPolicy.same(it, selected) }.coerceAtLeast(0)
                if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
            }
            withFrameNanos { }
            (when { target == "programme" && selected != null -> selectedFocus; else -> dateFocus }).requestFocus()
            restored = true
        }
    }
    LaunchedEffect(restoreDate) { if (restoreDate > 0) { withFrameNanos { }; dateFocus.requestFocus() } }
    BackHandler(enabled = interactive && hasFocus && !calendar) { returnToChannel() }
    Box(Modifier.fillMaxSize().testTag("channel_programme_panel").onFocusChanged {
        hasFocus = it.hasFocus
        if (it.hasFocus) onContentFocus()
    }
        // ← never leaves the stage by itself: past the date row it returns to the selected channel,
        // not to whichever row of the list is level with the focus.
        .focusProperties { exit = { if (it == FocusDirection.Left) FocusRequester.Cancel else FocusRequester.Default } }
        .focusGroup().onPreviewKeyEvent {
        if (it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown && target == "programme") { returnToChannel(); true } else false
    }.onKeyEvent {
        if (it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown) { if (!focusManager.moveFocus(FocusDirection.Left)) returnToChannel(); true } else false
    }) {
        val current = if (past) null else rows.firstOrNull { it.start <= now && it.end > now }
        val describesCurrent = current != null && ProgrammeBrowserPolicy.same(current, selected)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label(channel.name, Modifier.weight(1f, fill = false), size = 16, bold = true, maxLines = 1)
                    if (channel.number > 0) Label(channel.number.toString(), Modifier.clearAndSetSemantics { }, size = 12, color = Muted, maxLines = 1)
                }
                Label(timeLabel(now), Modifier.clearAndSetSemantics { }, size = 16, maxLines = 1)
            }
            if (!past) StageHero(channel, current, current?.description?.takeIf { describesCurrent && it.isNotBlank() }, now, preview, capture = interactive, playlistLocation)
            if (archive) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TvAction("Предыдущий день", Modifier.size(36.dp).testTag("programme_previous_day"), icon = Icons.Outlined.ChevronLeft, iconOnly = true,
                    enabled = date > earliest, onFocus = { target = "date" }) { actions.stepProgrammeDay(-1); restoreDate++ }
                TvAction(if (date == today) "Сегодня, ${date.format(dayMonthFormat)}"
                    else date.format(dayMonthWeekdayFormat),
                    Modifier.focusRequester(dateFocus).testTag("programme_date_picker"), icon = Icons.Outlined.CalendarMonth,
                    onFocus = { target = "date" }, maxLines = 1) { calendar = true }
                TvAction("Следующий день", Modifier.size(36.dp).testTag("programme_next_day"), icon = Icons.Outlined.ChevronRight, iconOnly = true,
                    enabled = date < today, onFocus = { target = "date" }) { actions.stepProgrammeDay(1); restoreDate++ }
                Spacer(Modifier.weight(1f))
                // In the date row, not in the column: a refresh must not push the schedule down.
                if (browser.load.refreshing) LoadingIndicator(Modifier.size(16.dp))
                if (past) TvAction("К текущей", Modifier.testTag("programme_current"), quiet = true,
                    onFocus = { target = "date" }) { actions.currentProgramme(); restoreDate++ }
            }
            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("Сегодня · ${today.format(dayMonthFormat)}", Modifier.weight(1f), size = 12, color = Muted)
                if (browser.load.refreshing) LoadingIndicator(Modifier.size(16.dp))
            }
            if (browser.load.phase == LoadPhase.ERROR) RetryLoad("Не удалось загрузить телепрограмму", actions::retryProgrammeDay)
            val dayLoading = browser.load.phase == LoadPhase.LOADING
            if (rows.isEmpty()) Box(Modifier.weight(1f).testTag("programme_empty_day").then(if (!archive) Modifier.focusRequester(dateFocus).focusable(interactive) else Modifier),
                contentAlignment = if (dayLoading) Alignment.TopStart else Alignment.CenterStart) {
                // A loading day keeps the outline of its rows; the verdicts are shown only once the day has answered.
                if (dayLoading) ScheduleSkeleton(4, Modifier.clipToBounds().semantics { contentDescription = "Загрузка телепрограммы" })
                else Label(if (browser.load.phase == LoadPhase.ERROR) "Телепрограмма недоступна" else "Нет телепрограммы за эту дату", size = 14, color = Muted)
            } else LazyColumn(state = listState, modifier = Modifier.weight(1f).then(if (!archive) Modifier.focusRequester(dateFocus) else Modifier).testTag("programme_day_list"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(rows, key = { "${it.sourceId}:${it.id}" }) { programme ->
                    val isCurrent = programme.start <= now && programme.end > now
                    // The status involves an archive lookup; the clock tick must not repeat it for rows it cannot change.
                    val status = if (isCurrent) "Сейчас" else remember(channel, programme, programme.start >= now) { ProgrammeBrowserPolicy.status(channel, programme, now) }
                    val archived = !isCurrent && programme.end <= now
                    TvAction(programme.title, Modifier.fillMaxWidth().testTag("programme_row:${programme.id}").semantics { if (isCurrent) stateDescription = "Сейчас в эфире" }
                        .then(if (ProgrammeBrowserPolicy.same(programme, selected)) Modifier.focusRequester(selectedFocus) else Modifier),
                        selected = isCurrent, quiet = true, onFocus = { target = "programme"; actions.selectBrowserProgramme(programme) },
                        textContent = { foreground ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Label(timeLabel(programme.start), size = 13, color = foreground.copy(alpha = .75f), maxLines = 1)
                                Label(programme.title, Modifier.weight(1f), size = 14, bold = isCurrent, color = foreground, maxLines = 1)
                                Label(status, size = 12, maxLines = 1,
                                    color = if (archived && foreground == LocalPalette.current.on) LocalPalette.current.archive else foreground.copy(alpha = .75f))
                            }
                        }) { actions.playBrowserProgramme(programme) }
                }
            }
            val unavailable = selected?.takeIf { it.end <= now }?.let {
                CatchupResolver.resolve(PlaybackRequest.Archive(channel, it), now) as? CatchupResult.Unavailable
            }
            if (unavailable != null) Label(unavailable.reason, size = 12, color = Muted, maxLines = 2)
            if (!describesCurrent && selected?.description?.isNotBlank() == true) Label(selected.description, Modifier.testTag("programme_description"), size = 12, color = Muted, maxLines = 2)
        }
    }
    if (calendar && archive) ProgrammeDatePicker(date, earliest, today, channel.name,
        onSelect = { actions.selectProgrammeDate(it); calendar = false; restoreDate++ },
        onDismiss = { calendar = false; restoreDate++ })
}

/**
 * The selected channel's frame with what is on now set over it. The bottom edge is the programme's
 * progress, so the stage answers "what is it and how far along" without a glance at the list.
 */
@Composable
private fun StageHero(channel: Channel, current: Programme?, description: String?, now: Long, preview: Boolean, capture: Boolean, playlistLocation: String?) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(PanelShape)) {
        // Under a layer or during a screen change the frame stays as it is; only new captures wait.
        ChannelFrame(channel, preview, Modifier.fillMaxSize(), playlistLocation, captureEnabled = preview && capture)
        if (current != null) {
            val progress = ProgrammeBrowserPolicy.progress(current, now)
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, .45f to Color.Black.copy(alpha = .6f), 1f to Color.Black.copy(alpha = .9f)))
                .padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                LiveIndicator("В эфире", Modifier.testTag("programme_live_marker"), color = LiveOnVideo)
                Label(current.title, size = 24, bold = true, color = Color.White, maxLines = 2)
                Label("${timeLabel(current.start)}–${timeLabel(current.end)} · ещё ${progress.remainingMinutes} мин", size = 13, color = Color.White.copy(alpha = .85f), maxLines = 1)
                if (description != null) Label(description, Modifier.testTag("programme_description"), size = 12, color = Color.White.copy(alpha = .8f), maxLines = 2)
            }
            ProgressLine(progress.fraction, Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).testTag("programme_live_progress")
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress.fraction, 0f..1f) },
                track = Color.White.copy(alpha = .2f), fill = LocalPalette.current.accentBright)
        }
    }
}
