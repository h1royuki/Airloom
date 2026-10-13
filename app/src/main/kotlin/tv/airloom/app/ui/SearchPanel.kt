package tv.airloom.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import tv.airloom.app.LoadPhase
import tv.airloom.app.R
import tv.airloom.app.UiActions
import tv.airloom.app.UiState
import tv.airloom.core.Channel
import tv.airloom.core.Programme
import tv.airloom.core.SearchLiveResult

/** The rail and the channel list together, less the card's own inset: results appear where the list was, clear of the stage. */
internal val SearchPanelWidth = 428.dp

/**
 * Search is a layer over the channel screen: a card that grows out of the rail's place. The screen under it
 * is dimmed and takes no focus. [open] drives one animated value that is read only while drawing.
 */
@Composable
internal fun SearchPanel(state: UiState, actions: UiActions, open: Boolean, voiceInput: VoiceInput? = null) {
    val expansion = animateFloatAsState(if (open) 1f else 0f, tween(motionDuration(200)), label = "search panel")
    val closing by remember { derivedStateOf { expansion.value > 0f } }
    if (!open && !closing) return
    val palette = LocalPalette.current
    val systemVoice = rememberVoiceInput()
    val voice = voiceInput ?: systemVoice
    val currentActions by rememberUpdatedState(actions)
    val field = remember { FocusRequester() }
    val microphone = remember { FocusRequester() }
    val mounted = remember { booleanArrayOf(true) }
    val fieldFocused = remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var restoreMicrophone by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { onDispose { mounted[0] = false } }
    LaunchedEffect(open) { if (open) { withFrameNanos { }; runCatching { field.requestFocus() } } }
    LaunchedEffect(restoreMicrophone) {
        if (restoreMicrophone > 0) { withFrameNanos { }; runCatching { microphone.requestFocus() } }
    }

    val query = state.searchQuery
    val blank = query.isBlank()
    val loading = state.searchLoad.phase == LoadPhase.LOADING
    val live = state.searchLive
    val archive = state.searchArchive
    // The list in display order: channels found by name, channels found by what is on them now, then the archive.
    val entries = remember(live, archive, query) {
        buildList {
            if (blank) { if (live.isNotEmpty()) { add(SearchEntry.Section("Последние каналы", null, "recent")); live.forEach { add(SearchEntry.Live(it)) } } }
            else {
                val (named, onAir) = live.partition { it.channel.name.contains(query, ignoreCase = true) }
                if (named.isNotEmpty()) { add(SearchEntry.Section("Каналы", named.size, "named")); named.forEach { add(SearchEntry.Live(it)) } }
                if (onAir.isNotEmpty()) { add(SearchEntry.Section("Сейчас в эфире", onAir.size, "on-air")); onAir.forEach { add(SearchEntry.Live(it)) } }
                if (archive.isNotEmpty()) { add(SearchEntry.Section("В архиве", archive.size, "archive")); archive.forEach { (channel, programme) -> add(SearchEntry.Archive(channel, programme)) } }
            }
        }
    }
    val highlight = if (blank) "" else query
    val empty = live.isEmpty() && archive.isEmpty()
    val searching = delayedLoading(loading && !empty)
    val rowHeight = when (state.settings.density) { 0 -> 48; 2 -> 66; else -> 56 }
    val numbered = remember(live, archive) { live.any { it.channel.number > 0 } || archive.any { it.first.number > 0 } }
    val logos = state.settings.logos
    val locations = remember(state.playlists) { state.playlists.associate { it.id to it.location } }
    val now = state.now
    val today = remember(now / 3_600_000) { Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate() }
    val noProgramme = if (loading) "" else "Нет программы"
    val listState = rememberLazyListState()
    val anchor = remember { FocusRequester() }
    // The row that owns the actions popup, or the row the focus steps to when its neighbour is hidden.
    var anchorKey by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var restoreRow by remember { mutableIntStateOf(0) }
    var hiding by remember { mutableStateOf<Channel?>(null) }
    LaunchedEffect(restoreRow) { if (restoreRow > 0) { withFrameNanos { }; runCatching { anchor.requestFocus() } } }
    // Hiding a channel removes its rows. Focus steps to a row of another channel first, so no focused row is ever removed.
    LaunchedEffect(hiding) {
        val channel = hiding ?: return@LaunchedEffect
        val from = entries.indexOfFirst { it.key == anchorKey }
        val others = entries.withIndex().filter { (_, entry) -> entry.channelId.let { it != null && it != channel.id } }
        val target = others.firstOrNull { it.index > from } ?: others.lastOrNull { it.index < from }
        if (target == null) runCatching { field.requestFocus() }
        else {
            anchorKey = target.value.key
            val info = listState.layoutInfo
            if (info.visibleItemsInfo.none { it.index == target.index && it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }) listState.scrollToItem(target.index)
            withFrameNanos { }
            runCatching { anchor.requestFocus() }
        }
        withFrameNanos { }
        actions.setHidden(channel, true)
        hiding = null
    }

    Box(Modifier.fillMaxSize().testTag("search_layer")) {
        Box(Modifier.fillMaxSize().drawBehind { val amount = expansion.value; if (amount > 0f) drawRect(Color.Black, alpha = .6f * amount) })
        CompositionLocalProvider(LocalUiInteractive provides open, LocalNavigationFocus provides null, LocalSettingsFocus provides null) {
            Column(
                Modifier.width(SearchPanelWidth).fillMaxHeight().testTag("search_panel")
                    .graphicsLayer { alpha = expansion.value }
                    // Laid out at full width once; opening only moves the clip, like the rail's drawer.
                    .drawWithContent {
                        val inset = DrawerInset.toPx()
                        val visible = RailWidth.toPx() + (size.width - RailWidth.toPx()) * expansion.value
                        clipRect(right = visible) {
                            drawRoundRect(palette.panel, Offset(inset, inset), Size(visible - inset, size.height - inset * 2), CornerRadius(28.dp.toPx()))
                            this@drawWithContent.drawContent()
                        }
                    }
                    .focusGroup()
                    .padding(start = DrawerInset + 10.dp, end = 16.dp, top = SafeVertical, bottom = SafeVertical),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    // The mark stays where the rail shows it, so the card reads as the rail grown wider.
                    Box(Modifier.width(RailSlot), contentAlignment = Alignment.Center) { AirloomMark(Modifier.size(32.dp)) }
                    SearchField(query, actions::search, if (!blank && !loading && !empty) live.size + archive.size else null,
                        Modifier.weight(1f), field, onFocus = { fieldFocused.value = it })
                    Spacer(Modifier.width(6.dp))
                    TvAction("Голосовой поиск", Modifier.size(40.dp).focusRequester(microphone).testTag("search_microphone"), icon = Icons.Outlined.Mic, iconOnly = true, enabled = !listening) {
                        listening = true; voiceError = null
                        val receive: (VoiceInputResult) -> Unit = { result ->
                            if (mounted[0]) {
                                listening = false
                                when (result) {
                                    is VoiceInputResult.Recognized -> if (result.text.isNotBlank()) currentActions.search(result.text.trim())
                                    is VoiceInputResult.Failure -> voiceError = result.message
                                    VoiceInputResult.Cancelled -> Unit
                                }
                                restoreMicrophone++
                            }
                        }
                        try { voice.launch(receive) }
                        catch (_: Exception) { receive(VoiceInputResult.Failure("Не удалось открыть голосовой ввод.")) }
                    }
                }
                voiceError?.let { Label(it, Modifier.padding(horizontal = 12.dp), color = Muted, size = 12, maxLines = 2) }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("search_results"), state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    when {
                        blank && live.isEmpty() -> item(key = "prompt") { SearchNote(stringResource(R.string.ui_search_prompt), "Наберите название канала или передачи. Справа от поля — голосовой поиск.") }
                        !blank && state.searchLoad.phase == LoadPhase.ERROR -> item(key = "error") { RetryLoad(stringResource(R.string.ui_search_failed)) { actions.search(query) } }
                        !blank && empty && loading -> item(key = "skeleton") { SkeletonRows(count = 6, height = rowHeight, numbered = true) }
                        !blank && empty -> item(key = "none") { SearchNote(stringResource(R.string.ui_search_no_results), "Попробуйте другое название или обновите телепрограмму.") }
                        else -> {
                            items(entries, key = { it.key }, contentType = { it::class }) { entry ->
                                val anchored = if (entry.key == anchorKey) Modifier.focusRequester(anchor) else Modifier
                                when (entry) {
                                    is SearchEntry.Section -> SearchSection(entry.title, entry.count, if (entry.key == "archive") Modifier.testTag("search_archive_section") else Modifier)
                                    is SearchEntry.Live -> {
                                        val channel = entry.result.channel
                                        val programme = entry.result.programme
                                        // Whole percents, as in the channel list: the clock tick redraws a row only when its bar moves.
                                        val progress = programme?.let { ((now - it.start) * 100 / (it.end - it.start).coerceAtLeast(1)).toInt().coerceIn(0, 100) }
                                        Box {
                                            SearchRow(channel, channel.name, programme?.title ?: noProgramme, progress, numbered, logos, rowHeight, locations[channel.playlistId], highlight,
                                                "search_live:${channel.id}", "${channel.name}: ${programme?.title ?: "Нет программы"}", anchored,
                                                onLongClick = { anchorKey = entry.key; menuOpen = true }) { actions.activate(channel, null) }
                                            if (menuOpen && entry.key == anchorKey) {
                                                val close: () -> Unit = { menuOpen = false; restoreRow++ }
                                                // The channel list's own popup. Pinning and moving belong to a list, and search results are not one.
                                                RowActionsPopup(channel.name, false, close, onPin = null, onMove = null,
                                                    favourite = channel.favourite, onFavourite = { close(); actions.setFavourite(channel, !channel.favourite) },
                                                    onHide = { menuOpen = false; hiding = channel })
                                            }
                                        }
                                    }
                                    is SearchEntry.Archive -> {
                                        val channel = entry.channel
                                        val programme = entry.programme
                                        SearchRow(channel, programme.title, "${channel.name} · ${archiveTime(programme.start, programme.end, today)}", null, numbered, logos, rowHeight,
                                            locations[channel.playlistId], highlight, "search_archive:${channel.id}:${programme.id}", "${programme.title}: ${channel.name}", anchored,
                                            onLongClick = null) { actions.activate(channel, programme) }
                                    }
                                }
                            }
                            if (searching) item(key = "searching") {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    LoadingIndicator(Modifier.size(16.dp))
                                    Label("Ищем в телепрограмме", size = 12, color = Muted, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                SearchHints(fieldFocused, rows = !empty)
            }
        }
    }
}

/** One line of the results list, in display order. */
private sealed interface SearchEntry {
    val key: String
    data class Section(val title: String, val count: Int?, override val key: String) : SearchEntry
    data class Live(val result: SearchLiveResult) : SearchEntry { override val key get() = "ch:${result.channel.id}" }
    data class Archive(val channel: Channel, val programme: Programme) : SearchEntry { override val key get() = "p:${channel.id}:${programme.sourceId}:${programme.id}" }
}
private val SearchEntry.channelId: String? get() = when (this) {
    is SearchEntry.Live -> result.channel.id
    is SearchEntry.Archive -> channel.id
    is SearchEntry.Section -> null
}

/** A result row. It draws the shared channel row and takes plain values only. */
@Composable
private fun SearchRow(channel: Channel, title: String, subtitle: String, progress: Int?, numbered: Boolean, logos: Boolean, height: Int,
    playlistLocation: String?, highlight: String, tag: String, description: String, modifier: Modifier, onLongClick: (() -> Unit)?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val interactive = LocalUiInteractive.current
    Row(modifier.fillMaxWidth().height(height.dp).testTag(tag)
        .focusHighlight(focused, scale = 1.02f)
        .onFocusChanged { focused = it.isFocused }
        .dpadClickable(onLongClick = onLongClick, onClick = onClick)
        .semantics(mergeDescendants = true) {
            contentDescription = description
            if (!interactive) disabled()
            if (onLongClick != null) onLongClick { if (interactive) onLongClick.invoke(); interactive }
            onClick { if (interactive) onClick(); interactive }
        }
        .focusable(interactive).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChannelRowBody(channel, subtitle, progress, numbered, focused, selected = false, playing = false, logos = logos,
            playlistLocation = playlistLocation, title = title, highlight = highlight)
    }
}

/**
 * The query field. Focus is the light fill, as everywhere else; OK opens the TV keyboard and «Назад» or ↓ closes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, count: Int?, modifier: Modifier, requester: FocusRequester, onFocus: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    val interactive = LocalUiInteractive.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var keyboardWasVisible by remember { mutableStateOf(false) }
    val finishEditing = { editing = false; keyboard?.hide(); Unit }
    val label = stringResource(R.string.ui_search_query)
    val editHint = stringResource(R.string.ui_entry_edit_hint)
    val doneHint = stringResource(R.string.ui_entry_done_hint)
    BackHandler(enabled = editing) { finishEditing() }
    LaunchedEffect(editing) {
        if (editing) { withFrameNanos { }; keyboard?.show() }
        else keyboardWasVisible = false
    }
    // A TV IME may consume Back itself. Its closing also ends our edit mode.
    LaunchedEffect(imeVisible, editing) {
        if (editing && imeVisible) keyboardWasVisible = true
        else if (editing && keyboardWasVisible) editing = false
    }
    val foreground = if (focused) palette.onFocus else palette.on
    val quiet = if (focused) palette.onFocus.copy(alpha = .7f) else palette.onVariant
    Row(modifier.height(40.dp).focusHighlight(focused, palette.container)
        .onPreviewKeyEvent { event ->
            if (!focused) return@onPreviewKeyEvent false
            val down = event.type == KeyEventType.KeyDown
            when (event.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    if (down && event.nativeKeyEvent.repeatCount == 0) { if (editing) finishEditing() else editing = true }
                    true
                }
                Key.DirectionUp -> { if (down && editing) finishEditing(); true }
                Key.DirectionDown -> {
                    if (down) { if (editing) finishEditing(); focusManager.moveFocus(FocusDirection.Down) }
                    true
                }
                Key.DirectionLeft, Key.DirectionRight -> {
                    if (editing) false
                    else { if (down && event.key == Key.DirectionRight) focusManager.moveFocus(FocusDirection.Right); true }
                }
                else -> false
            }
        }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Search, null, Modifier.size(19.dp), tint = quiet)
        BasicTextField(value, onChange, Modifier.weight(1f).focusRequester(requester).testTag("search_field").semantics {
            contentDescription = label
            stateDescription = if (editing) doneHint else editHint
            onClick { requester.requestFocus(); editing = true; true }
        }.onFocusChanged {
            focused = it.isFocused; onFocus(it.isFocused)
            if (!it.isFocused && editing) finishEditing()
        }, readOnly = !editing, enabled = interactive, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { finishEditing() }),
            textStyle = TextStyle(color = foreground, fontSize = (15 * LocalTextScale.current).sp),
            cursorBrush = SolidColor(if (focused) palette.accentOnFocus else palette.accent),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Label(stringResource(R.string.ui_search_placeholder), Modifier.clearAndSetSemantics { }, color = quiet, size = 15, maxLines = 1)
                    inner()
                }
            })
        if (count != null) Label(count.toString(), Modifier.testTag("search_count"), size = 12, color = quiet, maxLines = 1)
    }
}

@Composable
private fun SearchSection(title: String, count: Int?, modifier: Modifier = Modifier) {
    Label(if (count == null) title else "$title · $count", modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), size = 12, color = Muted, maxLines = 1)
}

@Composable
private fun SearchNote(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(title, size = 15, bold = true, maxLines = 2)
        Label(description, size = 12, color = Muted, maxLines = 3)
    }
}

/** The legend follows the focus; it reads a state object so only this row is redrawn when the focus moves. */
@Composable
private fun SearchHints(fieldFocused: State<Boolean>, rows: Boolean) {
    val modifier = Modifier.padding(start = 12.dp).clearAndSetSemantics { }
    when {
        fieldFocused.value -> RemoteHints("OK" to "клавиатура", "Назад" to "закрыть", modifier = modifier)
        rows -> RemoteHints("OK" to "смотреть", "OK долго" to "действия", "Назад" to "закрыть", modifier = modifier)
        else -> RemoteHints("Назад" to "закрыть", modifier = modifier)
    }
}

/** «сегодня 18:00–18:30», «вчера …» or the date: an archive result says when it was on. */
private fun archiveTime(start: Long, end: Long, today: LocalDate): String {
    val day = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate()
    val label = when (day) { today -> "сегодня"; today.minusDays(1) -> "вчера"; else -> day.format(dayMonthFormat) }
    return "$label ${timeLabel(start)}–${timeLabel(end)}"
}
