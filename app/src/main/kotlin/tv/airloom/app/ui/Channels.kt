package tv.airloom.app.ui

import androidx.compose.ui.res.stringResource
import tv.airloom.app.R

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.Tv
import androidx.tv.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import tv.airloom.app.*
import tv.airloom.core.Channel
import tv.airloom.core.Programme
import tv.airloom.core.label

/** Rail, channel list and stage. The stage shows the selected channel: its frame, what is on, and its programme. */
@Composable
internal fun ChannelsScreen(state: UiState, actions: UiActions, overlay: Boolean = false) {
    val interactive = LocalUiInteractive.current
    val rows = state.channels
    val showSkeleton = delayedLoading(state.channelLoad.phase == LoadPhase.LOADING)
    val focusedId = state.focusedChannel?.id
    val selected = remember(rows, focusedId) { focusedId?.let { id -> rows.firstOrNull { it.id == id } } ?: rows.firstOrNull() }
    val listState = rememberLazyListState()
    var menuChannelId by remember { mutableStateOf<String?>(null) }
    var restoreFocus by remember { mutableIntStateOf(0) }
    var hiding by remember { mutableStateOf<Pair<Channel, Channel?>?>(null) }
    val neighbourFocus = remember { FocusRequester() }
    var contentFocused by rememberSaveable { mutableStateOf(false) }
    val initialFocus = remember { FocusRequester() }
    val programmeEntry = remember { FocusRequester() }
    ReportEpgWindow(listState, rows, actions::setVisibleChannels)
    LaunchedEffect(state.channelSelection, state.selectedPlaylistId, rows.isNotEmpty()) {
        if (!interactive) return@LaunchedEffect
        val index = rows.indexOfFirst { it.id == selected?.id }.coerceAtLeast(0)
        if (rows.isNotEmpty()) {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
            withFrameNanos { }
            if (!contentFocused) runCatching { initialFocus.requestFocus() }
        }
    }
    // A layer over the screen (search) takes the focus away; when it closes, the selected row gets it back.
    val covered = remember { booleanArrayOf(false) }
    LaunchedEffect(interactive) {
        if (!interactive) covered[0] = true
        else if (covered[0]) {
            covered[0] = false
            val index = rows.indexOfFirst { it.id == selected?.id }
            if (index >= 0) {
                if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
                withFrameNanos { }
                runCatching { initialFocus.requestFocus() }
            }
        }
    }
    LaunchedEffect(state.movingIds, state.channelListPreferences, restoreFocus) {
        if (menuChannelId == null && rows.isNotEmpty() && (state.movingChannelId != null || restoreFocus > 0)) {
            val index = rows.indexOfFirst { it.id == (state.movingChannelId ?: selected?.id) }.coerceAtLeast(0)
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }) listState.scrollToItem(index)
            withFrameNanos { }; runCatching { initialFocus.requestFocus() }
        }
    }
    // Hiding the focused row: focus steps to its neighbour first. Removing a focused row would make the
    // system pick a row itself, and that row would then become the selected channel.
    LaunchedEffect(hiding) {
        val (channel, neighbour) = hiding ?: return@LaunchedEffect
        if (neighbour != null) {
            val index = rows.indexOfFirst { it.id == neighbour.id }
            val info = listState.layoutInfo
            if (index >= 0 && info.visibleItemsInfo.none { it.index == index && it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }) listState.scrollToItem(index)
            withFrameNanos { }
            runCatching { neighbourFocus.requestFocus() }
            withFrameNanos { }
        }
        actions.setHidden(channel, true)
        hiding = null
    }
    // Rows receive only what they draw, so a state change redraws the rows it actually affects.
    val recent = state.channelSelection == tv.airloom.core.ChannelSelection.Recent
    val preferencesCurrent = state.channelListScope() == state.channelPreferenceScope
    val pinnedIds = remember(state.channelListPreferences, preferencesCurrent) {
        if (preferencesCurrent) state.channelListPreferences.filter { it.pinned }.mapTo(HashSet()) { it.channelId } else emptySet()
    }
    val playlistLocations = remember(state.playlists) { state.playlists.associate { it.id to it.location } }
    val rowHeight = when (state.settings.density) { 0 -> 48; 2 -> 66; else -> 56 }
    val numbered = remember(rows) { rows.any { it.number > 0 } }
    NavigationLayout(state, actions, translucent = overlay) {
        Column(Modifier.width(336.dp).fillMaxHeight().padding(start = 16.dp, top = SafeVertical, bottom = SafeVertical)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = 12.dp).testTag("channels_header"),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(state.channelSelection.label, Modifier.weight(1f), size = 22, bold = true, maxLines = 1)
                if (rows.isNotEmpty()) Label(rows.size.toString(), Modifier.clearAndSetSemantics { }, size = 12, color = Muted, maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            if (state.movingChannelId != null) MoveHint(state.moveSaving)
            if (state.channelLoad.refreshing || state.busy) LoadingIndicator(Modifier.size(20.dp))
            if (showSkeleton) SkeletonRows(height = rowHeight)
            if (state.channelLoad.phase == LoadPhase.ERROR) RetryLoad(stringResource(R.string.ui_channels_load_failed), actions::retryCatalogue)
            if (rows.isEmpty() && state.channelLoad.phase == LoadPhase.READY) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("Список пуст", size = 16, color = Muted, maxLines = 1)
                    Label(if (recent) "Открытые каналы появятся здесь." else "Выберите другую категорию.", size = 12, color = Muted, maxLines = 3)
                }
            }
            ChannelList(Modifier.weight(1f).fillMaxWidth(), rows, state.programmes, state.now, state.epgLoad.phase == LoadPhase.READY, numbered,
                selectedId = selected?.id, playingId = state.player.channel?.id, movingId = state.movingChannelId, moveSaving = state.moveSaving,
                menuChannelId = menuChannelId, pinnedIds = pinnedIds, recent = recent, logos = state.settings.logos, rowHeight = rowHeight,
                playlistLocations = playlistLocations, listState = listState, initialFocus = initialFocus, programmeEntry = programmeEntry,
                neighbourId = hiding?.second?.id, neighbourFocus = neighbourFocus,
                actions = actions, onRowFocused = { contentFocused = false }, onMenu = { menuChannelId = it },
                onMenuClosed = { menuChannelId = null; restoreFocus++ },
                onHide = { channel, neighbour -> menuChannelId = null; hiding = channel to neighbour })
            if (rows.isNotEmpty() && state.movingChannelId == null)
                RemoteHints("OK долго" to "действия", "Назад" to "меню", modifier = Modifier.padding(start = 12.dp, top = 10.dp).clearAndSetSemantics { })
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp, end = SafeHorizontal, top = SafeVertical, bottom = SafeVertical)) {
            if (selected == null && state.channelLoad.phase == LoadPhase.READY) {
                Column(Modifier.fillMaxSize().padding(28.dp).testTag("channels_empty_details"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
                    Icon(if (recent) Icons.Outlined.History else Icons.Outlined.Tv,
                        contentDescription = null, modifier = Modifier.size(36.dp), tint = LocalAccent.current)
                    Label(if (recent) "Здесь будут последние каналы" else "Нет каналов в этой категории", size = 22, bold = true, maxLines = 2)
                    Label("Выберите канал из общего списка.", size = 14, color = Muted, maxLines = 2)
                    TvAction("Все каналы", icon = Icons.Outlined.GridView) { actions.selectCategory("Все каналы") }
                }
            } else if (selected != null) {
                val today = ProgrammeBrowserPolicy.today(state.now)
                val viewport = state.programmes[selected.id].orEmpty()
                // Until the day query answers for a newly focused channel, show its viewport programmes.
                val browser = state.programmeBrowser.takeIf { it.channelId == selected.id }
                    ?: remember(selected.id, today, viewport, state.epgLoad) {
                        ProgrammeBrowserState(channelId = selected.id, loadedDate = today, programmes = viewport, load = state.epgLoad)
                    }
                ChannelProgrammePanel(selected, browser, state.now, state.settings.preview, playlistLocations[selected.playlistId], actions,
                    restoreContentFocus = contentFocused, entryFocus = programmeEntry,
                    onContentFocus = { contentFocused = true },
                    returnToChannel = { contentFocused = false; initialFocus.requestFocus() })
            }
        }
    }
}

@Composable
private fun ChannelList(
    modifier: Modifier, rows: List<Channel>, programmes: Map<String, List<Programme>>, now: Long, epgReady: Boolean, numbered: Boolean,
    selectedId: String?, playingId: String?, movingId: String?, moveSaving: Boolean, menuChannelId: String?,
    pinnedIds: Set<String>, recent: Boolean, logos: Boolean, rowHeight: Int, playlistLocations: Map<String, String>,
    listState: LazyListState, initialFocus: FocusRequester, programmeEntry: FocusRequester, neighbourId: String?, neighbourFocus: FocusRequester,
    actions: UiActions, onRowFocused: () -> Unit, onMenu: (String) -> Unit, onMenuClosed: () -> Unit, onHide: (Channel, Channel?) -> Unit,
) {
    LazyColumn(state = listState, modifier = modifier.testTag("channels_list"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(rows, key = Channel::id) { channel ->
            val current = programmes[channel.id]?.firstOrNull { it.start <= now && it.end > now }
            // Whole percents: the clock tick redraws a row only when its bar actually moves.
            val progress = current?.let { ((now - it.start) * 100 / (it.end - it.start).coerceAtLeast(1)).toInt().coerceIn(0, 100) }
            val pinned = channel.id in pinnedIds
            Box(Modifier.fillMaxWidth().animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = androidx.compose.animation.core.tween(motionDuration(150)))) {
                // A row with a programme does not depend on the loading phase and is not redrawn when it flips.
                ChannelRow(channel, current, progress, numbered, epgReady || current != null, selected = channel.id == selectedId, playing = channel.id == playingId,
                    moving = movingId == channel.id, moveSaving = moveSaving, pinned = pinned, logos = logos, height = rowHeight,
                    playlistLocation = playlistLocations[channel.playlistId], initialFocus = initialFocus, programmeEntry = programmeEntry,
                    neighbourFocus = neighbourFocus.takeIf { channel.id == neighbourId }, actions = actions, onFocused = onRowFocused, onMenu = onMenu)
                if (menuChannelId == channel.id) {
                    RowActionsPopup(channel.name, pinned, onMenuClosed,
                        favourite = channel.favourite,
                        onFavourite = { onMenuClosed(); actions.setFavourite(channel, !channel.favourite) },
                        onPin = if (recent) null else ({ onMenuClosed(); actions.setChannelPinned(channel, !pinned) }),
                        onMove = if (recent) null else ({ onMenuClosed(); actions.startMove(channel) }),
                        onHide = {
                            val remaining = rows.filterNot { it.id == channel.id }
                            onHide(channel, remaining.getOrNull(rows.indexOf(channel).coerceAtMost((remaining.size - 1).coerceAtLeast(0))))
                        })
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel, programme: Programme?, progress: Int?, numbered: Boolean, epgReady: Boolean, selected: Boolean, playing: Boolean, moving: Boolean,
    moveSaving: Boolean, pinned: Boolean, logos: Boolean, height: Int, playlistLocation: String?,
    initialFocus: FocusRequester, programmeEntry: FocusRequester, neighbourFocus: FocusRequester?, actions: UiActions, onFocused: () -> Unit, onMenu: (String) -> Unit,
) {
    val interactive = LocalUiInteractive.current
    val palette = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var longHandled by remember { mutableStateOf(false) }
    Row(Modifier.moveKeys(moving, moveSaving, actions::moveChannel, actions::finishMove).navigationReturnFocus().fillMaxWidth()
        .height(height.dp)
        .then(if (selected) Modifier.focusRequester(initialFocus) else Modifier)
        .then(if (neighbourFocus != null) Modifier.focusRequester(neighbourFocus) else Modifier)
        // The selected row keeps a tonal mark while focus is in the programme or the rail.
        .focusHighlight(focused, if (selected) palette.accentContainer else Color.Transparent, RowShape, 1.02f)
        .focusProperties { right = programmeEntry }
        .onFocusChanged { focused = it.isFocused; if (focused) { onFocused(); actions.focusProgramme(channel, programme) } else { pressed = false; longHandled = false } }
        .onPreviewKeyEvent { event ->
            if (!interactive) return@onPreviewKeyEvent false
            val down = event.type == KeyEventType.KeyDown
            when {
                moving && down && event.key == Key.DirectionUp -> { actions.moveChannel(-1); true }
                moving && down && event.key == Key.DirectionDown -> { actions.moveChannel(1); true }
                moving && event.type == KeyEventType.KeyUp && event.key in listOf(Key.DirectionCenter, Key.Enter) -> { actions.finishMove(true); true }
                event.key in listOf(Key.DirectionCenter, Key.Enter) -> {
                    if (down && event.nativeKeyEvent.repeatCount == 0) { pressed = true; longHandled = false }
                    else if (down && pressed && event.nativeKeyEvent.repeatCount > 0 && !longHandled) { longHandled = true; onMenu(channel.id) }
                    else if (event.type == KeyEventType.KeyUp) { if (pressed && !longHandled) actions.play(channel); pressed = false; longHandled = false }
                    true
                }
                else -> false
            }
        }
        .semantics(mergeDescendants = true) {
            contentDescription = "${channel.name}: ${programme?.title ?: "Нет программы"}"
            onClick { actions.play(channel); true }
            onLongClick { if (!moving) onMenu(channel.id); !moving }
        }
        .focusable(interactive).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChannelRowBody(channel, programme?.title ?: if (epgReady) "Нет программы" else "", progress, numbered, focused, selected, playing, logos, playlistLocation)
        val mark = if (focused) palette.accentOnFocus else if (selected) palette.onAccentContainer else palette.accent
        if (pinned) Icon(Icons.Outlined.PushPin, "Закреплён", Modifier.size(14.dp), tint = mark)
        if (moving) Label("↕", size = 18, color = mark)
    }
}

/**
 * What a channel row draws, shared by the main list, the player panel and search: number, logo, name,
 * what is on and how far along it is. It takes plain values only, so a row is redrawn when one of them changes.
 * Search sets [title] to a programme for archive rows and marks [highlight] in both lines.
 */
@Composable
internal fun RowScope.ChannelRowBody(channel: Channel, programmeTitle: String, progress: Int?, numbered: Boolean, focused: Boolean,
    selected: Boolean, playing: Boolean, logos: Boolean, playlistLocation: String?, title: String = channel.name, highlight: String = "") {
    val palette = LocalPalette.current
    val primary = when { focused -> palette.onFocus; selected -> palette.onAccentContainer; else -> palette.on }
    val secondary = when { focused -> palette.onFocus.copy(alpha = .7f); selected -> palette.onAccentContainer.copy(alpha = .78f); else -> palette.onVariant }
    val accent = when { focused -> palette.accentOnFocus; selected -> palette.onAccentContainer; else -> palette.accent }
    if (numbered) Label(if (channel.number > 0) channel.number.toString() else "", Modifier.width(26.dp).clearAndSetSemantics { }, size = 12, color = secondary, maxLines = 1)
    ChannelLogo(channel, logos, 16, Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(palette.tile), playlistLocation)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val titleColor = if (playing && !focused && !selected) palette.accent else primary
        if (highlight.isEmpty()) {
            Label(title, size = 15, bold = true, color = titleColor, maxLines = 1)
            Label(programmeTitle, size = 12, color = secondary, maxLines = 1)
        } else {
            Label(remember(title, highlight, accent) { markMatches(title, highlight, accent) }, size = 15, bold = true, color = titleColor, maxLines = 1)
            Label(remember(programmeTitle, highlight, accent) { markMatches(programmeTitle, highlight, accent) }, size = 12, color = secondary, maxLines = 1)
        }
        if (progress != null) ProgressLine(progress / 100f, Modifier.fillMaxWidth(), track = primary.copy(alpha = .15f), fill = accent)
    }
    if (playing) Icon(Icons.Outlined.Equalizer, null, Modifier.size(18.dp), tint = accent)
}

/** Asks for EPG around the viewport; the request changes once per window step, not on every row. */
@Composable
internal fun ReportEpgWindow(listState: LazyListState, rows: List<Channel>, report: (List<String>) -> Unit) {
    val current by rememberUpdatedState(report)
    LaunchedEffect(listState, rows) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index?.div(UiPolicies.EPG_WINDOW_STEP) ?: -1 }
            .distinctUntilChanged().collect { bucket -> current(UiPolicies.epgWindow(rows, bucket * UiPolicies.EPG_WINDOW_STEP)) }
    }
}
