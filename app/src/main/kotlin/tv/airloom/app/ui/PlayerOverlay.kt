package tv.airloom.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import tv.airloom.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.delay
import tv.airloom.app.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import tv.airloom.core.PlaybackRequest
import tv.airloom.core.Programme
import tv.airloom.playback.PlaybackState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.clearAndSetSemantics

/** [progress] carries live playback positions; without it the timeline reads them from [state]. */
@Composable
fun AirloomPlayerOverlay(state: UiState, actions: UiActions, progress: StateFlow<PlaybackState>? = null) {
    if (state.screen != Screen.PLAYER) return
    var interactionToken by remember { mutableIntStateOf(0) }
    var videoFocused by remember { mutableStateOf(false) }
    val timelineFocusedState = remember { mutableStateOf(false) }
    val timelineFocused by timelineFocusedState
    val draftState = remember(state.player.request) { mutableStateOf<Long?>(null) }
    var draftPosition by draftState
    val programme = (state.player.request as? PlaybackRequest.Archive)?.programme
        ?: state.playingProgramme.takeIf { state.player.request is PlaybackRequest.Live }
    val videoFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val timelineFocus = remember { FocusRequester() }
    LaunchedEffect(state.playerControls, state.quickChannels) {
        draftPosition = null
        if (!state.playerControls && !state.quickChannels) { withFrameNanos { }; runCatching { videoFocus.requestFocus() } }
        else if (state.playerControls && !state.quickChannels && state.player.request != null) {
            withFrameNanos { }; runCatching { playFocus.requestFocus() }
        }
    }
    LaunchedEffect(state.playerControls, state.settings.playerControlsSeconds, state.quickChannels,
        state.player.error, state.player.isPlaying, state.player.isBuffering, timelineFocused, interactionToken) {
        if (state.playerControls && state.player.request != null && UiPolicies.autoHide(state.player.isPlaying, state.player.isBuffering, state.quickChannels, timelineFocused, state.player.error != null) && state.settings.playerControlsSeconds > 0) {
            delay(state.settings.playerControlsSeconds * 1_000L)
            actions.showPlayerControls(false)
        }
    }
    androidx.activity.compose.BackHandler(enabled = !state.quickChannels && (draftPosition != null || state.playerControls && state.player.request != null)) {
        when {
            draftPosition != null -> draftPosition = null
            else -> actions.showPlayerControls(false)
        }
    }
    AirloomTheme(accent = state.settings.accent.color(), textScale = listOf(.9f, 1f, 1.15f)[state.settings.textSize.coerceIn(0, 2)]) {
        // Opening a stream is shown by the controls themselves. The spinner is for a stall in a stream that
        // was already playing, where a short one passes unnoticed, and for opening with the controls hidden.
        val connecting = state.player.isBuffering && !state.player.hasRenderedFrame
        val controlsShown = state.playerControls && state.player.request != null && state.player.error == null && !state.quickChannels
        val buffering = delayedLoading(state.player.isBuffering && !(connecting && controlsShown), if (state.player.hasRenderedFrame) 500 else 150)
        // The idle frame comes up with the screen, settling from slightly larger. Read only while drawing.
        val opening = remember { Animatable(0f) }
        val openingDuration = motionDuration(Motion.Screen)
        LaunchedEffect(Unit) { opening.animateTo(1f, tween(openingDuration, easing = Motion.Decelerate)) }
        Box(Modifier.fillMaxSize().focusRequester(videoFocus).onFocusChanged { videoFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (state.quickChannels || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                interactionToken++
                when (event.key) {
                    Key.ChannelUp -> { actions.nextChannel(1); true }
                    Key.ChannelDown -> { actions.nextChannel(-1); true }
                    Key.DirectionUp, Key.DirectionDown -> if (videoFocused && !state.playerControls && state.player.error == null) { actions.showQuickChannels(true); true } else false
                    Key.DirectionCenter, Key.Enter -> if (videoFocused && !state.playerControls && state.player.error == null) { actions.showPlayerControls(true); true } else false
                    // Only the archive steps with the arrows: a live stream's seek window is just its buffer.
                    Key.DirectionLeft, Key.DirectionRight -> if (videoFocused && !state.playerControls && state.player.error == null
                        && state.player.request is PlaybackRequest.Archive && state.player.isSeekable) {
                        actions.playerSeek(if (event.key == Key.DirectionLeft) -10_000 else 10_000); true
                    } else false
                    Key.MediaPlayPause -> { actions.playerTogglePause(); true }
                    else -> false
                }
            }.focusable()) {
            val pendingChannel = state.player.channel ?: state.focusedChannel?.takeIf { it.id == state.settings.lastChannelId }
            // It stays composed while it dissolves into the first video frame instead of being cut away.
            FadingPresence(!state.player.hasRenderedFrame && pendingChannel != null, Modifier.fillMaxSize(), Motion.Screen) {
                if (pendingChannel != null) Box(Modifier.fillMaxSize().graphicsLayer {
                    alpha = opening.value; val scale = 1.06f - .06f * opening.value; scaleX = scale; scaleY = scale
                }) {
                    ChannelFrame(pendingChannel, state.settings.preview, Modifier.fillMaxSize().testTag("player_idle_background"),
                        state.playlists.firstOrNull { it.id == pendingChannel.playlistId }?.location,
                        fullscreen = true, captureEnabled = state.player.request == null && state.settings.preview)
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .85f), Color.Black.copy(alpha = .25f)))))
                }
            }
            if (state.player.request == null) {
                Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(.65f).padding(start = SafeHorizontal, end = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Label(pendingChannel?.name ?: stringResource(R.string.ui_choose_channel), size = 32, bold = true, maxLines = 2)
                    if (pendingChannel != null) {
                        Label(stringResource(R.string.ui_idle_play_hint), size = 16, color = Muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            InitialFocus { TvAction(stringResource(R.string.ui_idle_watch), it, icon = Icons.Outlined.PlayArrow, onClick = actions::playerTogglePause) }
                            TvAction(stringResource(R.string.ui_open_channels)) { actions.navigate(Screen.GUIDE) }
                        }
                    } else InitialFocus { TvAction(stringResource(R.string.ui_open_channels), it) { actions.navigate(Screen.GUIDE) } }
                }
            }
            state.player.recovery?.let { recovery ->
                Column(Modifier.align(Alignment.Center).width(460.dp).clip(PanelShape).background(Color.Black.copy(alpha = .75f)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("Восстанавливаем соединение — попытка ${recovery.attempt} из ${recovery.maximum}", Modifier.testTag("player_recovery"), size = 17)
                    if (!state.player.playWhenReady) Label("На паузе. Нажмите «Смотреть», чтобы продолжить.", size = 13)
                }
            }
            FadingPresence(buffering && state.player.recovery == null, Modifier.align(Alignment.Center), Motion.Swap) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .6f)), contentAlignment = Alignment.Center) {
                    LoadingIndicator(Modifier.testTag("player_buffering"), label = stringResource(R.string.ui_loading_video))
                }
            }
            state.player.error?.let { error ->
                Column(Modifier.align(Alignment.Center).width(440.dp).clip(PanelShape)
                    .background(LocalPalette.current.panel.copy(alpha = .96f)).padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Label(stringResource(R.string.ui_playback_failed), size = 22, bold = true)
                    Label(error, size = 14, color = Muted)
                    Label("Переподключений: ${state.player.reconnectAttempts}", size = 12, color = Muted)
                    if (state.player.restartRequired) InitialFocus { TvAction("Начать заново", it, onClick = actions::playerRestart) }
                    else InitialFocus { TvAction(stringResource(R.string.ui_retry), it, onClick = actions::playerRetry) }
                    TvAction("Настройки сети", onClick = actions::playerNetworkSettings)
                    TvAction(stringResource(R.string.ui_return_channels)) { actions.navigate(Screen.GUIDE) }
                }
            }
            // A passing notice of what is next. The controls say the same themselves, so it yields to them.
            val upcoming = state.announcedNext(programme)
            var notice by remember { mutableStateOf<Programme?>(null) }
            var noticeShown by remember { mutableStateOf(false) }
            LaunchedEffect(upcoming?.id) {
                noticeShown = upcoming != null
                if (upcoming != null) { notice = upcoming; delay(UiPolicies.NEXT_NOTICE_MS); noticeShown = false }
            }
            AnimatedVisibility(visible = noticeShown && !state.playerControls && !state.quickChannels && state.player.error == null,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = SafeHorizontal, bottom = SafeVertical),
                enter = fadeIn(tween(motionDuration(180))) + slideInVertically(tween(motionDuration(180))) { 12 },
                exit = fadeOut(tween(motionDuration(180))) + slideOutVertically(tween(motionDuration(180))) { 12 }) {
                val channel = state.player.channel
                notice?.let { NextNotice(channel, state.settings.logos, state.playlists.firstOrNull { p -> p.id == channel?.playlistId }?.location, it) }
            }
            AnimatedVisibility(visible = state.playerControls && state.player.request != null && state.player.error == null && !state.quickChannels,
                enter = fadeIn(tween(motionDuration(180))) + slideInVertically(tween(motionDuration(180))) { 12 },
                exit = fadeOut(tween(motionDuration(180))) + slideOutVertically(tween(motionDuration(180))) { 12 }) {
                PlayerControls(state, progress, programme, actions, draftState, timelineFocusedState, playFocus, timelineFocus)
            }
            PlayerChannelsPanel(state, actions, open = state.quickChannels)
        }
    }
}

/** The programme after [current], once the live programme is in its last minutes. */
private fun UiState.announcedNext(current: Programme?): Programme? =
    playingNext?.takeIf { player.request is PlaybackRequest.Live && current != null && UiPolicies.announcesNext(current.end, now) }

/** Says what follows the live programme. It never takes focus: the remote keeps driving the video. */
@Composable
private fun NextNotice(channel: tv.airloom.core.Channel?, logos: Boolean, playlistLocation: String?, next: Programme) {
    Row(Modifier.testTag("player_next_notice").widthIn(max = 480.dp).clip(PanelShape).background(Color.Black.copy(alpha = .8f))
        .padding(start = 10.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ChannelLogo(channel, logos, 14, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .14f)), playlistLocation)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label(listOfNotNull("Далее", channel?.name?.takeIf(String::isNotBlank)?.let { "на «$it»" }, "· в ${timeLabel(next.start)}").joinToString(" "),
                size = 12, color = Color.White.copy(alpha = .72f), maxLines = 1)
            Label(next.title, size = 15, bold = true, color = Color.White, maxLines = 1)
        }
    }
}

/** Position ticks twice a second: only this panel follows them, and only while it is on screen. */
@Composable
private fun PlayerControls(state: UiState, progress: StateFlow<PlaybackState>?, programme: Programme?, actions: UiActions,
                           draftState: MutableState<Long?>, timelineFocusedState: MutableState<Boolean>,
                           playFocus: FocusRequester, timelineFocus: FocusRequester) {
    val player = progress?.collectAsStateWithLifecycle()?.value ?: state.player
    var draftPosition by draftState
    var timelineFocused by timelineFocusedState
    val archive = player.request is PlaybackRequest.Archive
    val connecting = player.isBuffering && !player.hasRenderedFrame
    CompositionLocalProvider(LocalUiInteractive provides (state.playerControls && !state.quickChannels && player.error == null)) {
        Box(Modifier.fillMaxSize()) {
            // Channel and clock sit at the top, so the lower third carries only the title, the timeline and one row of buttons.
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .55f), 1f to Color.Transparent))
                .padding(start = SafeHorizontal, end = SafeHorizontal, top = SafeVertical, bottom = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(player.channel, state.settings.logos, 14,
                    Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(alpha = .35f)).testTag("player_channel_logo"),
                    state.playlists.firstOrNull { it.id == player.channel?.playlistId }?.location)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label(player.channel?.name.orEmpty(), Modifier.weight(1f, fill = false).testTag("player_channel_name"), size = 16, bold = true, color = Color.White, maxLines = 1)
                    player.channel?.number?.takeIf { it > 0 }?.let { Label(it.toString(), Modifier.clearAndSetSemantics { }, size = 12, color = Color.White.copy(alpha = .7f), maxLines = 1) }
                }
                Label(timeLabel(state.now), Modifier.clearAndSetSemantics { }, size = 16, color = Color.White, maxLines = 1)
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(
                0f to Color.Transparent, .25f to Color.Black.copy(alpha = .3f), 1f to Color.Black.copy(alpha = .85f)))
                .padding(start = SafeHorizontal, end = SafeHorizontal, top = 44.dp, bottom = SafeVertical), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val canSeek = archive && player.isSeekable
                val duration = player.durationMs.takeIf { it > 0 } ?: programme?.let { (it.end - it.start).coerceAtLeast(0) } ?: 0
                val position = draftPosition ?: player.positionMs
                val timeline = when {
                    archive && duration > 0 -> Triple((position.toFloat() / duration).coerceIn(0f, 1f), programme?.let { timeLabel(it.start) } ?: "0:00", programme?.let { timeLabel(it.start + duration) } ?: durationLabel(duration))
                    !archive && programme != null -> Triple(((state.now - programme.start).toFloat() / (programme.end - programme.start).coerceAtLeast(1)).coerceIn(0f, 1f), timeLabel(programme.start), timeLabel(programme.end))
                    // Live without a programme has nothing to measure: the player position there is only the buffer.
                    else -> null
                }
                // Without a timeline the sweep runs in the gap under the information instead.
                Column(Modifier.fillMaxWidth().testTag("player_information").connectingSweep(connecting && timeline == null, below = 3.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label(programme?.title?.takeIf(String::isNotBlank) ?: player.channel?.name.orEmpty(),
                        Modifier.fillMaxWidth(.72f).testTag("player_title"), size = 30, bold = true, color = Color.White, maxLines = 2)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (archive) Label(stringResource(R.string.ui_player_archive), Modifier.testTag("player_channel_mode"), size = 14, bold = true, color = Peach, maxLines = 1)
                        else LiveIndicator(stringResource(R.string.ui_player_live), Modifier.testTag("player_channel_mode"), size = 14, color = LiveOnVideo)
                        if (archive) programme?.let { Label("${timeLabel(it.start)}–${timeLabel(it.end)}", size = 14, color = Color.White.copy(alpha = .85f), maxLines = 1) }
                        if (connecting) Label("Подключение…", Modifier.testTag("player_connecting"), size = 14, color = Color.White.copy(alpha = .85f), maxLines = 1)
                        // Only the live view says what is next, and only while the current programme is ending.
                        val next = state.announcedNext(programme)
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            if (next != null) Label("Далее в ${timeLabel(next.start)} · ${next.title}", Modifier.testTag("player_next_programme"),
                                size = 14, color = Color.White.copy(alpha = .75f), maxLines = 1)
                        }
                    }
                }
                val interactive = LocalUiInteractive.current
                if (timeline != null) key(player.request, programme?.id) {
                    val time = if (archive && programme != null) timeLabel(programme.start + position)
                        else if (!archive && programme != null) timeLabel(state.now) else durationLabel(position)
                    ProgrammeTimeline(timeline.first, timeline.second, timeline.third, time,
                        Modifier.focusRequester(timelineFocus).onFocusChanged { timelineFocused = it.isFocused }
                            .focusProperties { down = playFocus }
                            .onPreviewKeyEvent { event ->
                                if (!interactive || !canSeek || !timelineFocused) return@onPreviewKeyEvent false
                                if (event.type == KeyEventType.KeyDown) when (event.key) {
                                    Key.DirectionLeft, Key.DirectionRight -> {
                                        val delta = if (event.key == Key.DirectionLeft) -10_000 else 10_000
                                        draftPosition = UiPolicies.draftSeek(draftPosition ?: player.positionMs, delta.toLong(), duration)
                                        true
                                    }
                                    Key.DirectionDown -> { draftPosition = null; playFocus.requestFocus(); true }
                                    Key.DirectionCenter, Key.Enter -> {
                                        draftPosition?.let(actions::playerSeekTo)
                                        draftPosition = null
                                        true
                                    }
                                    else -> false
                                } else event.key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Enter)
                            }.semantics { contentDescription = "Позиция воспроизведения"; if (!interactive) disabled() }
                            .focusable(interactive && canSeek), focused = timelineFocused, draft = draftPosition != null, connecting = connecting)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val playing = player.playWhenReady || player.isPlaying
                        PlayerButton(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            if (playing) stringResource(R.string.ui_player_pause) else "Смотреть", Modifier.focusRequester(playFocus)
                                .focusProperties { if (timeline != null && canSeek) up = timelineFocus }, labelled = true,
                            onClick = actions::playerTogglePause)
                        if (canSeek) {
                            PlayerButton(Icons.Outlined.Replay30, stringResource(R.string.ui_seek_back)) { actions.playerSeek(-30_000) }
                            PlayerButton(Icons.Outlined.Forward30, stringResource(R.string.ui_seek_forward)) { actions.playerSeek(30_000) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayerButton(Icons.Outlined.List, "Каналы", labelled = true) { actions.showQuickChannels(true) }
                        if (archive) PlayerButton(Icons.Outlined.LiveTv, stringResource(R.string.ui_return_live), labelled = true, onClick = actions::playerLive)
                    }
                }
            }
        }
    }
}

/**
 * Pill button over video. The two frequent actions always carry a label; the rest are icons that
 * open into a labelled pill while focused, so the row stays short and nothing is left unexplained.
 */
@Composable
private fun PlayerButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, labelled: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val interactive = LocalUiInteractive.current
    val showLabel = labelled || focused
    val foreground = if (focused) LocalPalette.current.onFocus else Color.White
    Row(modifier.height(44.dp).widthIn(min = 44.dp)
        .focusHighlight(focused, Color.White.copy(alpha = .14f), CircleShape, 1.04f)
        .onFocusChanged { focused = it.isFocused }
        .dpadClickable(onClick = onClick)
        .semantics(mergeDescendants = true) {
            role = Role.Button
            if (!labelled) contentDescription = label
            if (!interactive) disabled()
            onClick { if (interactive) onClick(); interactive }
        }
        .focusable(interactive).padding(start = if (showLabel) 16.dp else 0.dp, end = if (showLabel) 20.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = foreground)
        if (showLabel) Label(label, if (labelled) Modifier else Modifier.clearAndSetSemantics { }, size = 15, bold = true, color = foreground, maxLines = 1)
    }
}
/**
 * A highlight running along the element while a stream is being opened: over the track of a timeline, or
 * as a thin line [below] an element that has none. Drawn over the content and read only while drawing.
 */
@Composable
private fun Modifier.connectingSweep(active: Boolean, below: Dp? = null): Modifier {
    if (!active || !LocalMotionEnabled.current) return this
    val turn = rememberInfiniteTransition(label = "connecting").animateFloat(0f, 1f,
        infiniteRepeatable(tween(1_200, easing = LinearEasing)), label = "sweep")
    val accent = LocalPalette.current.accentBright
    return drawWithContent {
        drawContent()
        val width = size.width * .28f
        val start = -width + (size.width + width) * turn.value
        val left = start.coerceAtLeast(0f)
        val right = (start + width).coerceAtMost(size.width)
        if (below == null) drawRect(Color.White, Offset(left, 0f), Size(right - left, size.height), alpha = .55f)
        else drawRoundRect(accent, Offset(left, size.height + below.toPx()), Size(right - left, 3.dp.toPx()), CornerRadius(1.5.dp.toPx()))
    }
}
private fun durationLabel(ms: Long): String { val seconds = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(seconds / 60, seconds % 60) }

/** Programme start and end flank the track; the current time rides above the thumb. */
@Composable
internal fun ProgrammeTimeline(fraction: Float, start: String, end: String, position: String, modifier: Modifier = Modifier, focused: Boolean = false, draft: Boolean = false, connecting: Boolean = false) {
    val progress by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(motionDuration(200)), label = "programme position")
    val density = LocalDensity.current
    val accent = LocalPalette.current.accentBright
    var labelWidth by remember { mutableIntStateOf(0) }
    val thumb = 14.dp
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (focused) Color.White.copy(alpha = .1f) else Color.Transparent).padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
        Label(start, Modifier.padding(bottom = 2.dp).testTag("programme_start_time"), size = 12, color = Color.White.copy(alpha = .75f), maxLines = 1)
        Column(Modifier.weight(1f)) {
            BoxWithConstraints(Modifier.fillMaxWidth().height((20 * LocalTextScale.current).dp)) {
                val widthPx = with(density) { maxWidth.roundToPx() }
                val thumbPx = with(density) { thumb.toPx() }
                // Centred on the thumb, whose centre travels between half a thumb from either edge.
                Label(if (draft) "$position · OK" else position, Modifier.offset {
                    IntOffset((thumbPx / 2 + (widthPx - thumbPx) * progress - labelWidth / 2f).toInt().coerceIn(0, (widthPx - labelWidth).coerceAtLeast(0)), 0)
                }.onSizeChanged { labelWidth = it.width }.testTag("programme_position_time"), size = 12, bold = true, color = accent, maxLines = 1)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(20.dp)) {
                Box(Modifier.padding(horizontal = thumb / 2).fillMaxWidth().height(6.dp).align(Alignment.Center).clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = .24f)).connectingSweep(connecting).testTag("programme_progress")) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(accent).testTag("programme_progress_fill"))
                }
                Box(Modifier.align(Alignment.CenterStart).offset { IntOffset(((maxWidth - thumb).toPx() * progress).toInt(), 0) }.size(thumb).clip(CircleShape).background(Color.White).testTag("programme_progress_dot"))
            }
        }
        Label(end, Modifier.padding(bottom = 2.dp).testTag("programme_end_time"), size = 12, color = Color.White.copy(alpha = .75f), maxLines = 1)
    }
}
