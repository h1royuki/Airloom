package tv.airloom.app.ui

import androidx.compose.ui.res.stringResource
import tv.airloom.app.R

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import tv.airloom.app.*
import tv.airloom.core.*

@Composable
fun AirloomApp(state: UiState, actions: UiActions, playerBounds: (Rect?) -> Unit) {
    var playerErrorDismissed by remember(state.player.request, state.player.error) { mutableStateOf(false) }
    val screenState = rememberSaveableStateHolder()
    val accent = state.settings.accent.color()
    AirloomTheme(light = state.settings.theme == ThemeMode.LIGHT || state.settings.theme == ThemeMode.SYSTEM && !isSystemInDarkTheme(), accent = accent, textScale = listOf(.9f, 1f, 1.15f)[state.settings.textSize.coerceIn(0,2)]) {
        CompositionLocalProvider(LocalProviderProfile provides state.provider) {
        BackHandler { actions.back() }
        val transitionDuration = motionDuration(160)
        val startupDuration = motionDuration(Motion.Screen)
        // The text outlives the message by the length of its exit.
        val lastMessage = remember { arrayOfNulls<String>(1) }
        state.message?.let { lastMessage[0] = it }
        val messageDuration = motionDuration(Motion.Enter)
        Box(Modifier.fillMaxSize()) {
            if (!state.loading && state.startupError != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(Modifier.width(420.dp)) { RetryLoad(state.startupError, actions::retryStartup) }
            }
            // Search is a layer over the channels, not a screen: both share one piece of content.
            // The start-up mark is the first target (null), so it dissolves into the first screen instead of being cut.
            else AnimatedContent(if (state.loading) null else if (state.screen == Screen.SEARCH) Screen.GUIDE else state.screen, transitionSpec = {
                val duration = if (initialState == null) startupDuration else transitionDuration
                fadeIn(tween(duration)) togetherWith fadeOut(tween(duration))
            }, label = "screen") { screen ->
                if (screen == null) StartupLoading()
                else screenState.SaveableStateProvider(screen.name) {
                val current = screen == state.screen || screen == Screen.GUIDE && state.screen == Screen.SEARCH
                CompositionLocalProvider(LocalUiInteractive provides current) { when (screen) {
                Screen.ONBOARDING -> OnboardingScreen(state.onboarding, state.settings, state.importResult, state.progress, state.error, state.busy, WizardCallbacks(
                    change = actions::updateOnboarding,
                    importPlaylist = actions::importOnboardingPlaylist, importEpg = actions::importOnboardingEpg, skipEpg = actions::skipEpg, cancel = actions::cancelImport,
                    settings = actions::updateSettings, finish = actions::finishOnboarding, testPlayback = { state.allChannels.firstOrNull { it.playlistId == state.onboarding.playlistId }?.let { actions.play(it); actions.fullscreen() } }, exit = { actions.cancelImport(); actions.updateOnboarding(state.onboarding.copy(step = 6)); actions.navigate(Screen.SETTINGS) }), adding = state.onboarding.complete, saving = state.onboardingSaving)
                Screen.GUIDE, Screen.SEARCH -> {
                    val searching = current && state.screen == Screen.SEARCH
                    CompositionLocalProvider(LocalUiInteractive provides (current && !searching)) { ChannelsScreen(state, actions) }
                    SearchPanel(state, actions, open = searching)
                }
                Screen.SETTINGS -> SettingsScreen(state, actions)
                Screen.PLAYER -> Box(Modifier.fillMaxSize().background(Color.Black).onGloballyPositioned { playerBounds(it.boundsInRoot()) })
            }
            }
            }
            }
            AnimatedVisibility(state.message != null, Modifier.align(Alignment.BottomCenter).padding(bottom = SafeVertical),
                enter = fadeIn(tween(messageDuration)) + slideInVertically(tween(messageDuration)) { it / 3 },
                exit = fadeOut(tween(messageDuration)) + slideOutVertically(tween(messageDuration)) { it / 3 }) {
                Box(Modifier.clip(RoundedCornerShape(50)).background(LocalPalette.current.focus).padding(horizontal = 20.dp, vertical = 11.dp)) { Label(lastMessage[0].orEmpty(), size = 14, color = LocalPalette.current.onFocus) }
            }
        }
        if (state.error != null && state.screen != Screen.ONBOARDING) AirloomDialog(stringResource(R.string.ui_operation_failed), actions::dismissMessage) { Label(state.error, color = Peach, size = 14) }
        if (state.screen == Screen.GUIDE && state.player.error != null && state.error == null && !playerErrorDismissed) AirloomDialog(stringResource(R.string.ui_playback_failed), { playerErrorDismissed = true }) {
            Label(state.player.error.orEmpty(), size = 14, color = Peach)
            TvAction(stringResource(R.string.ui_retry)) { playerErrorDismissed = true; actions.playerRetry() }
        }
        state.detailProgramme?.let { programme ->
            val channel = state.detailChannel
            AirloomDialog(programme.title, actions::dismissDetail) {
                Label("${channel?.name.orEmpty()} · ${dayLabel(programme.start)} · ${timeLabel(programme.start)}–${timeLabel(programme.end)}", size = 12, color = Muted)
                if (programme.description.isNotBlank()) Label(programme.description, size = 14)
                val past = programme.end <= state.now
                val future = programme.start > state.now
                val available = channel != null && channel.catchup.days > 0 && programme.start >= state.now - channel.catchup.days * 86400000L
                when {
                    future -> Label(stringResource(R.string.ui_programme_future), size = 13, color = Muted)
                    past && !available -> Label(if ((channel?.catchup?.days ?: 0) > 0) "Срок хранения этой передачи истёк" else "Для этого канала архив недоступен", size = 13, color = Muted)
                    channel != null -> InitialFocus { TvAction(if (past) "Смотреть в архиве" else "Смотреть эфир", it) { actions.play(channel, if (past) programme else null); actions.dismissDetail(); actions.fullscreen() } }
                }
            }
        }
        }
    }
}
