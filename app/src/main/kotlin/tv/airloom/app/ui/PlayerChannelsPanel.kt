package tv.airloom.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tv.airloom.app.LoadPhase
import tv.airloom.app.R
import tv.airloom.app.UiActions
import tv.airloom.app.UiState
import tv.airloom.app.channelSelection
import tv.airloom.core.CategoryIcons
import tv.airloom.core.Channel
import tv.airloom.core.ChannelSelection
import tv.airloom.core.PlaybackRequest
import tv.airloom.core.label

/** The card and its inset from the screen edge. Rows come out 320 dp wide, as in the main channel list. */
internal val PlayerPanelWidth = 352.dp

/** A row on its way out of the list: the focus steps to [neighbourId] first, then [action] removes the row. */
private class Leaving(val neighbourId: String?, val action: () -> Unit)

/**
 * Channel switcher over the video, for D-pad Up/Down and the player's Channels button. It is the card of the
 * search and of the open rail, in their place, and lists the section the main screen shows, so its order is
 * the order of CH+/CH−. ← swaps the list for the sections, as ← on the main screen opens the rail.
 * [open] drives one animated value that is read only while drawing.
 */
@Composable
internal fun PlayerChannelsPanel(state: UiState, actions: UiActions, open: Boolean = true) {
    val expansion = animateFloatAsState(if (open) 1f else 0f, tween(motionDuration(200)), label = "player channels")
    val closing by remember { derivedStateOf { expansion.value > 0f } }
    if (!open && !closing) return
    val palette = LocalPalette.current
    val channels = state.channels
    val selection = state.channelSelection
    val playingId = state.player.channel?.id
    // Rows capture plain values, not the whole UI state, so unrelated updates leave the list untouched.
    val archivePlaying = state.player.request is PlaybackRequest.Archive
    // A failed stream is not playing: choosing its row opens it again.
    val livePlaying = state.player.request is PlaybackRequest.Live && state.player.error == null
    val showLogos = state.settings.logos
    val markFavourites = selection != ChannelSelection.Favourites
    val rowHeight = when (state.settings.density) { 0 -> 48; 2 -> 66; else -> 56 }
    val numbered = remember(channels) { channels.any { it.number > 0 } }
    val programmes = state.programmes
    val now = state.now
    val playlistLocations = remember(state.playlists) { state.playlists.associate { it.id to it.location } }
    val entries = remember(state.categories) { railEntries(state.categories.filterNot { it.hidden }.sortedBy { it.order }) }
    val selectedKey = selection.railKey()

    var sections by remember { mutableStateOf(false) }
    // The list the card is about to show: null while it is settled.
    var switchTo by remember { mutableStateOf<Boolean?>(null) }
    // The channels on screen when a section was chosen: the card waits for the new list before it shows rows again.
    var choosing by remember { mutableStateOf<List<Channel>?>(null) }
    var parked by remember { mutableStateOf(false) }
    var menuId by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf<Leaving?>(null) }
    var restore by remember { mutableIntStateOf(0) }
    // Plain holders: a focus move inside the card recomposes nothing but the two rows involved.
    val lastChannel = remember { arrayOfNulls<String>(1) }
    val contentFocused = remember { booleanArrayOf(false) }
    val headerFocus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    val sectionFocus = remember { FocusRequester() }
    // The card opens with the playing channel a couple of rows from the top.
    val listState = rememberLazyListState(remember { (channels.indexOfFirst { it.id == playingId } - 2).coerceAtLeast(0) })
    val sectionState = rememberLazyListState()
    val focusId = remember(channels, sections, restore, leaving, open) {
        leaving?.neighbourId ?: lastChannel[0]?.takeIf { id -> channels.any { it.id == id } }
            ?: playingId?.takeIf { id -> channels.any { it.id == id } } ?: channels.firstOrNull()?.id
    }
    val sectionFocusKey = remember(entries, selectedKey) { selectedKey.takeIf { key -> entries.any { it.key == key } } ?: entries.firstOrNull()?.key }
    val empty = !sections && channels.isEmpty()
    ReportEpgWindow(listState, channels) { actions.setVisibleChannels(it) }

    val leaveSections = { if (switchTo == null && choosing == null) switchTo = false }
    BackHandler(enabled = open) { if (sections) leaveSections() else actions.showQuickChannels(false) }
    // While one list replaces the other the focus waits on the header, so no focused row is ever removed.
    LaunchedEffect(switchTo) {
        val target = switchTo ?: return@LaunchedEffect
        parked = true
        withFrameNanos { }
        runCatching { headerFocus.requestFocus() }
        sections = target
        switchTo = null
    }
    // A chosen section's list is built off the main thread; an unchanged list never arrives, hence the timeout.
    LaunchedEffect(choosing, channels) {
        val before = choosing ?: return@LaunchedEffect
        if (channels === before) delay(300)
        choosing = null
        lastChannel[0] = null
        switchTo = false
    }
    // Focus goes to the list on screen whenever it holds none. EPG ticks and catalogue refreshes do not move it.
    LaunchedEffect(sections, channels, entries, open, restore) {
        if (!open || menuId != null || leaving != null || choosing != null) return@LaunchedEffect
        withFrameNanos { }
        if (sections) {
            if (!contentFocused[0]) {
                val index = entries.indexOfFirst { it.key == sectionFocusKey }.coerceAtLeast(0)
                if (!sectionState.shows(index)) sectionState.scrollToItem(index)
                withFrameNanos { }
                runCatching { sectionFocus.requestFocus() }
            }
        } else if (channels.isEmpty()) runCatching { headerFocus.requestFocus() }
        else if (!contentFocused[0]) {
            val index = channels.indexOfFirst { it.id == focusId }.coerceAtLeast(0)
            if (!listState.shows(index)) listState.scrollToItem((index - 2).coerceAtLeast(0))
            withFrameNanos { }
            runCatching { rowFocus.requestFocus() }
        }
        parked = false
    }
    // Hiding a channel, or dropping one from the favourites it is listed in, removes its row.
    LaunchedEffect(leaving) {
        val leave = leaving ?: return@LaunchedEffect
        withFrameNanos { }
        if (leave.neighbourId != null) {
            val index = channels.indexOfFirst { it.id == leave.neighbourId }
            if (index >= 0 && !listState.shows(index)) listState.scrollToItem(index)
            withFrameNanos { }
            runCatching { rowFocus.requestFocus() }
        } else {
            parked = true
            withFrameNanos { }
            runCatching { headerFocus.requestFocus() }
        }
        withFrameNanos { }
        leave.action()
        leaving = null
    }
    val leave: (Channel, () -> Unit) -> Unit = { channel, action ->
        val remaining = channels.filterNot { it.id == channel.id }
        menuId = null
        leaving = Leaving(remaining.getOrNull(channels.indexOf(channel).coerceAtMost(remaining.lastIndex))?.id, action)
    }
    val choose: (RailEntry) -> Unit = { entry ->
        if (switchTo == null && choosing == null) {
            if (entry.key == selectedKey) switchTo = false
            else {
                choosing = channels
                when (entry) {
                    RailEntry.Favourites -> actions.selectCategory("Избранное")
                    RailEntry.Recent -> actions.selectCategory("Последние")
                    RailEntry.All -> actions.selectCategory("Все каналы")
                    is RailEntry.Group -> actions.selectCategory(entry.category)
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()
        .onPreviewKeyEvent { event ->
            when (event.key) {
                Key.Back -> { if (event.type == KeyEventType.KeyUp) { if (sections) leaveSections() else actions.showQuickChannels(false) }; true }
                // ← leads from the channels to the sections, → back; neither leaves the card.
                Key.DirectionLeft -> { if (open && event.type == KeyEventType.KeyDown && !sections && switchTo == null) switchTo = true; true }
                Key.DirectionRight -> { if (open && event.type == KeyEventType.KeyDown && sections) leaveSections(); true }
                else -> false
            }
        }
        .testTag("player_channels_overlay")) {
        CompositionLocalProvider(LocalUiInteractive provides open) {
            Column(Modifier.width(PlayerPanelWidth).fillMaxHeight().testTag("player_channels_panel")
                .graphicsLayer { alpha = expansion.value }
                // Laid out at full width once; opening only moves the clip, like the rail's drawer and the search card.
                .drawWithContent {
                    val inset = DrawerInset.toPx()
                    val visible = size.width * expansion.value
                    clipRect(right = visible) {
                        drawRoundRect(palette.panel, Offset(inset, inset), Size((visible - inset).coerceAtLeast(0f), size.height - inset * 2), CornerRadius(28.dp.toPx()))
                        this@drawWithContent.drawContent()
                    }
                }
                .focusGroup()
                .padding(start = DrawerInset + 10.dp, end = 10.dp, top = SafeVertical, bottom = SafeVertical)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).testTag("player_channels_header")
                    .focusRequester(headerFocus).focusable(open && (parked || empty)).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label(if (sections) stringResource(R.string.ui_player_sections) else selection.label, Modifier.weight(1f), size = 22, bold = true, maxLines = 1)
                    if (!sections && channels.isNotEmpty()) Label(channels.size.toString(), Modifier.clearAndSetSemantics { }, size = 12, color = Muted, maxLines = 1)
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f).fillMaxWidth().onFocusChanged { contentFocused[0] = it.hasFocus }) {
                    if (sections) {
                        val shown = remember { mutableFloatStateOf(1f) }
                        LazyColumn(Modifier.fillMaxSize().testTag("player_sections_list"), state = sectionState, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            items(entries, key = { it.key }) { entry ->
                                RailItem(entry.title(), entry.icon(), entry.key == selectedKey, shown,
                                    if (entry.key == sectionFocusKey) Modifier.focusRequester(sectionFocus) else Modifier,
                                    pinned = entry is RailEntry.Group && entry.category.pinned) { choose(entry) }
                            }
                        }
                    } else if (channels.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.channelLoad.phase == LoadPhase.LOADING) Label(stringResource(R.string.ui_loading), size = 14, color = Muted, maxLines = 1)
                            else {
                                Label("Список пуст", size = 16, color = Muted, maxLines = 1)
                                Label(if (selection == ChannelSelection.Recent) "Открытые каналы появятся здесь." else "Выберите другой раздел.", size = 12, color = Muted, maxLines = 3)
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize().testTag("player_channels_list"), state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(channels, key = Channel::id, contentType = { "channel" }) { channel ->
                                val programme = programmes[channel.id]?.firstOrNull { it.start <= now && it.end > now }
                                // Whole percents, as in the channel list: the clock tick redraws a row only when its bar moves.
                                val progress = programme?.let { ((now - it.start) * 100 / (it.end - it.start).coerceAtLeast(1)).toInt().coerceIn(0, 100) }
                                Box {
                                    PlayerChannelRow(channel, programme?.title, progress, numbered, playing = channel.id == playingId,
                                        archivePlaying = archivePlaying, favourite = markFavourites && channel.favourite, showLogos = showLogos, height = rowHeight,
                                        playlistLocation = playlistLocations[channel.playlistId],
                                        modifier = (if (channel.id == focusId) Modifier.focusRequester(rowFocus) else Modifier)
                                            .onFocusChanged { if (it.isFocused) lastChannel[0] = channel.id },
                                        onMenu = { menuId = channel.id },
                                        onClick = {
                                            if (channel.id == playingId && livePlaying) actions.showQuickChannels(false)
                                            else actions.play(channel)
                                        })
                                    if (menuId == channel.id) {
                                        val close: () -> Unit = { menuId = null; restore++ }
                                        // The channel list's own popup. Pinning and moving belong to the main list.
                                        RowActionsPopup(channel.name, false, close, onPin = null, onMove = null,
                                            favourite = channel.favourite,
                                            onFavourite = {
                                                if (channel.favourite && selection == ChannelSelection.Favourites) leave(channel) { actions.setFavourite(channel, false) }
                                                else { close(); actions.setFavourite(channel, !channel.favourite) }
                                            },
                                            onHide = { leave(channel) { actions.setHidden(channel, true) } })
                                    }
                                }
                            }
                        }
                    }
                }
                val hints = Modifier.padding(start = 12.dp, top = 10.dp).clearAndSetSemantics { }
                if (sections) RemoteHints("OK" to "выбрать", "→" to "к каналам", modifier = hints)
                else if (channels.isEmpty()) RemoteHints("←" to "разделы", modifier = hints)
                else RemoteHints("←" to "разделы", "OK долго" to "действия", modifier = hints)
            }
        }
    }
}

private fun LazyListState.shows(index: Int): Boolean {
    val info = layoutInfo
    return info.visibleItemsInfo.any { it.index == index && it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }
}

@Composable
private fun RailEntry.title(): String = when (this) {
    RailEntry.Favourites -> stringResource(R.string.ui_favourites)
    RailEntry.Recent -> stringResource(R.string.ui_recent_channels)
    RailEntry.All -> stringResource(R.string.ui_all_channels)
    is RailEntry.Group -> category.name
}

private fun RailEntry.icon(): ImageVector = when (this) {
    RailEntry.Favourites -> Icons.Outlined.FavoriteBorder
    RailEntry.Recent -> Icons.Outlined.History
    RailEntry.All -> Icons.Outlined.GridView
    is RailEntry.Group -> CategoryIcons.resolve(category.name, category.iconOverride).vector()
}

/** The same row as the main channel list; the playing channel is marked by the accent, the focus by the light fill. */
@Composable
private fun PlayerChannelRow(channel: Channel, programmeTitle: String?, progress: Int?, numbered: Boolean, playing: Boolean, archivePlaying: Boolean, favourite: Boolean,
    showLogos: Boolean, height: Int, playlistLocation: String?, modifier: Modifier, onMenu: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val interactive = LocalUiInteractive.current
    val palette = LocalPalette.current
    Row(modifier.fillMaxWidth().height(height.dp)
        // The playing channel keeps a tonal mark, as the selected row does in the main list.
        .focusHighlight(focused, if (playing) palette.accentContainer else Color.Transparent, RowShape, 1.02f)
        .onFocusChanged { focused = it.isFocused }
        .dpadClickable(onLongClick = onMenu, onClick = onClick)
        .semantics(mergeDescendants = true) {
            contentDescription = channel.name + ": " + (programmeTitle ?: "Нет программы") + if (playing) { if (archivePlaying) ", смотрю архив" else ", в эфире" } else ""
            if (!interactive) disabled()
            onClick { if (interactive) onClick(); interactive }
            onLongClick { if (interactive) onMenu(); interactive }
        }
        .focusable(interactive).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChannelRowBody(channel, programmeTitle ?: "Нет программы", progress, numbered, focused, selected = playing, playing = playing, logos = showLogos, playlistLocation = playlistLocation, favourite = favourite)
        if (playing && archivePlaying) Label("Архив", Modifier.clearAndSetSemantics { }, size = 12, color = if (focused) palette.onFocus else palette.archive, maxLines = 1)
    }
}
