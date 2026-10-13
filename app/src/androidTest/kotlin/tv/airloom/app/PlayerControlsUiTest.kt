package tv.airloom.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tv.airloom.app.ui.AirloomPlayerOverlay
import tv.airloom.core.AppSettings
import tv.airloom.core.CatchupSpec
import tv.airloom.core.Channel
import tv.airloom.core.PlaybackRequest
import tv.airloom.core.Programme
import tv.airloom.playback.PlaybackState
import tv.airloom.playback.TrackOption

@OptIn(ExperimentalTestApi::class)
class PlayerControlsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val seeks = mutableListOf<Long>()
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
    // Recomposition compares the actions, so the proxy has to answer equals and hashCode.
    private val actions = Proxy.newProxyInstance(UiActions::class.java.classLoader, arrayOf(UiActions::class.java)) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "PlayerControlsActions"
            "playerSeek" -> { seeks += args!!.first() as Long; null }
            else -> { calls += (listOf(method.name) + args.orEmpty().map { it.toString() }).joinToString(":"); null }
        }
    } as UiActions

    @Test fun liveProgrammeProgressIsInformationalAndTransportIsLeftAligned() {
        val channel = Channel("channel", "playlist", "Мир ТВ", "https://example.invalid/stream.m3u8")
        val start = 1_000_000L
        val now = start + 17 * 60_000L
        val programme = Programme("programme", "epg", "channel", "В сердце природы", start, start + 50 * 60_000L)
        compose.setContent {
            AirloomPlayerOverlay(UiState(
                loading = false, screen = Screen.PLAYER, now = now,
                player = PlaybackState(request = PlaybackRequest.Live(channel), channel = channel, isPlaying = true, isSeekable = false),
                playingProgramme = programme,
            ), actions)
        }
        val logo = compose.onNodeWithTag("player_channel_logo").assertIsDisplayed().getUnclippedBoundsInRoot()
        val name = compose.onNodeWithTag("player_channel_name").assertIsDisplayed().getUnclippedBoundsInRoot()
        val mode = compose.onNodeWithText("Прямой эфир").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(logo.right < name.left)
        assertTrue(mode.top >= name.bottom)
        compose.onNodeWithText("С начала").assertDoesNotExist()
        compose.onNodeWithText("Звук").assertDoesNotExist()
        val track = compose.onNodeWithTag("programme_progress").assertIsDisplayed().getUnclippedBoundsInRoot()
        val fill = compose.onNodeWithTag("programme_progress_fill").getUnclippedBoundsInRoot()
        assertEquals(17f / 50f, (fill.right - fill.left).value / (track.right - track.left).value, .04f)
        compose.onNodeWithText("−30 с").assertDoesNotExist()
        compose.onNodeWithText("+30 с").assertDoesNotExist()
        val pause = compose.onNodeWithText("Пауза").getUnclippedBoundsInRoot()
        val channels = compose.onNodeWithText("Каналы").getUnclippedBoundsInRoot()
        assertTrue(pause.left < channels.left)
        compose.onNodeWithText("Формат").assertDoesNotExist()
        compose.onNodeWithContentDescription("Формат").assertDoesNotExist()
    }
    @Test fun nextProgrammeAppearsOnTheRightOnlyInTheLastFiveMinutes() {
        val channel = Channel("live", "p", "Эфир", "https://example.invalid/live")
        val start = 1_000_000L
        val programme = Programme("p", "e", "live", "Передача", start, start + 1_800_000)
        val next = Programme("n", "e", "live", "Новости", programme.end, programme.end + 1_800_000)
        val state = mutableStateOf(UiState(loading = false, screen = Screen.PLAYER, now = programme.end - 360_000,
            playingProgramme = programme, playingNext = next,
            player = PlaybackState(request = PlaybackRequest.Live(channel), channel = channel, isPlaying = true)))
        compose.setContent { AirloomPlayerOverlay(state.value, actions) }
        compose.onNodeWithText("Прямой эфир").assertIsDisplayed()
        compose.onNodeWithTag("player_next_programme").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(now = programme.end - 300_000) }
        val label = compose.onNodeWithTag("player_next_programme").assertIsDisplayed()
            .assertTextEquals("Далее в ${tv.airloom.app.ui.timeLabel(next.start)} · Новости").getUnclippedBoundsInRoot()
        // The label ends where the timeline ends: at the right safe margin.
        val end = compose.onNodeWithTag("programme_end_time").getUnclippedBoundsInRoot()
        assertEquals(end.right.value, label.right.value, 1f)
        // The controls already say it, so the notice stays away while they are open.
        compose.onNodeWithTag("player_next_notice").assertDoesNotExist()
    }
    @Test fun nextNoticeAppearsBottomRightWhileControlsAreHiddenAndLeavesByItself() {
        val channel = Channel("live", "p", "Эфир", "https://example.invalid/live")
        val start = 1_000_000L
        val programme = Programme("p", "e", "live", "Передача", start, start + 1_800_000)
        val next = Programme("n", "e", "live", "Новости", programme.end, programme.end + 1_800_000)
        val state = mutableStateOf(UiState(loading = false, screen = Screen.PLAYER, playerControls = false, now = programme.end - 360_000,
            playingProgramme = programme, playingNext = next,
            player = PlaybackState(request = PlaybackRequest.Live(channel), channel = channel, isPlaying = true)))
        compose.setContent { AirloomPlayerOverlay(state.value, actions) }
        compose.onNodeWithTag("player_next_notice").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(now = programme.end - 300_000) }
        compose.mainClock.advanceTimeBy(500)
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val notice = compose.onNodeWithTag("player_next_notice").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(root.right.value - 48f, notice.right.value, 1f)
        assertEquals(root.bottom.value - 27f, notice.bottom.value, 1f)
        compose.onNodeWithText("Далее на «Эфир» · в ${tv.airloom.app.ui.timeLabel(next.start)}").assertIsDisplayed()
        compose.onNodeWithText("Новости").assertIsDisplayed()
        compose.onNodeWithTag("player_next_notice").assert(isFocused().not())
        compose.mainClock.advanceTimeBy(8_000)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(12_000) { compose.onAllNodesWithTag("player_next_notice").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun arrowsStepTheArchiveButNotLive() {
        val channel = Channel("live", "p", "Эфир", "https://example.invalid/live")
        val programme = Programme("p", "e", "live", "Передача", 1_000_000, 2_800_000)
        val state = mutableStateOf(UiState(loading = false, screen = Screen.PLAYER, playerControls = false,
            player = PlaybackState(request = PlaybackRequest.Live(channel), channel = channel, isPlaying = true,
                isSeekable = true, durationMs = 30_000, positionMs = 5_000)))
        compose.setContent { AirloomPlayerOverlay(state.value, actions) }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
        compose.runOnIdle { state.value = state.value.copy(player = state.value.player.copy(request = PlaybackRequest.Archive(channel, programme))) }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(listOf(-10_000L, 10_000L), seeks) }
    }
    @Test fun seekableLiveStillShowsProgrammeClockAndNoArchiveTransport() {
        val channel = Channel("live", "p", "Эфир", "https://example.invalid/live")
        val start = 1_000_000L
        val now = start + 600_000L
        val programme = Programme("p", "e", "live", "Передача", start, start + 1_800_000)
        compose.setContent { AirloomPlayerOverlay(UiState(loading = false, screen = Screen.PLAYER, now = now,
            playingProgramme = programme, player = PlaybackState(request = PlaybackRequest.Live(channel), channel = channel,
                isPlaying = true, isSeekable = true, durationMs = 30_000, positionMs = 5_000)), actions) }
        compose.onNodeWithText("−30 с").assertDoesNotExist()
        compose.onNodeWithText("+30 с").assertDoesNotExist()
        compose.onNodeWithTag("programme_start_time").assertTextEquals(tv.airloom.app.ui.timeLabel(start))
        compose.onNodeWithTag("programme_end_time").assertTextEquals(tv.airloom.app.ui.timeLabel(programme.end))
        compose.onNodeWithTag("programme_position_time").assertTextEquals(tv.airloom.app.ui.timeLabel(now))
    }

    @Test fun liveWithAnArchiveRestartsAndSeveralAudioTracksOfferAChoice() {
        val channel = Channel("channel", "playlist", "Мир ТВ", "https://example.invalid/live/index.m3u8", catchup = CatchupSpec("flussonic", days = 3))
        val now = 2_000_000L
        val programme = Programme("programme", "epg", "channel", "В сердце природы", now - 17 * 60_000L, now + 33 * 60_000L)
        compose.setContent {
            AirloomPlayerOverlay(UiState(loading = false, screen = Screen.PLAYER, now = now, settings = AppSettings(playerControlsSeconds = 0),
                player = PlaybackState(request = PlaybackRequest.Live(channel), channel = channel, isPlaying = true,
                    audioTracks = listOf(TrackOption("0:0", "Русский · 2 кан.", true), TrackOption("0:1", "Английский · 2 кан.", false))),
                playingProgramme = programme), actions)
        }
        // «С начала» is spelled out and takes the place «К эфиру» has in the archive, after the channels.
        val channels = compose.onNodeWithText("Каналы").getUnclippedBoundsInRoot()
        val restart = compose.onNodeWithText("С начала").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(channels.right < restart.left)
        compose.onNodeWithText("К эфиру").assertDoesNotExist()
        compose.onNodeWithText("С начала").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertTrue("playerFromStart" in calls) }
        // The audio choice is an icon that names itself while focused.
        compose.onNodeWithContentDescription("Звук").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(2_000) { compose.onAllNodes(hasTestTag("player_audio_0") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player_audio_1").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertTrue("playerAudio:0:1" in calls) }
        compose.onNodeWithTag("player_audio_1").assertDoesNotExist()
        compose.waitUntil(2_000) { compose.onAllNodes(hasContentDescription("Звук") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun remoteMediaKeysAndDigitsDriveThePlayer() {
        val channel = Channel("live", "p", "Эфир", "https://example.invalid/live")
        val programme = Programme("p", "e", "live", "Передача", 1_000_000, 2_800_000)
        val state = mutableStateOf(UiState(loading = false, screen = Screen.PLAYER, playerControls = false,
            player = PlaybackState(request = PlaybackRequest.Archive(channel, programme), channel = channel, isPlaying = true,
                isSeekable = true, durationMs = 30_000, positionMs = 5_000)))
        compose.setContent { AirloomPlayerOverlay(state.value, actions) }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.MediaFastForward); pressKey(Key.MediaRewind) }
        compose.runOnIdle { assertEquals(listOf(30_000L, -30_000L), seeks); assertTrue("showPlayerControls:true" in calls) }
        compose.onRoot().performKeyInput { pressKey(Key.Five); pressKey(Key.ChannelUp); pressKey(Key.MediaPause); pressKey(Key.MediaPlay); pressKey(Key.MediaStop) }
        // Playing already, «play» has nothing to do; «pause» pauses.
        compose.runOnIdle { assertEquals(listOf("channelDigit:5", "nextChannel:1", "playerTogglePause", "back"), calls.filterNot { it.startsWith("showPlayerControls") }) }
        compose.runOnIdle { state.value = state.value.copy(numberEntry = NumberEntry("5", "Мир ТВ")) }
        compose.onNodeWithTag("player_channel_number").assertIsDisplayed()
        compose.onNode(hasText("Мир ТВ") and hasAnyAncestor(hasTestTag("player_channel_number"))).assertIsDisplayed()
        // OK opens the typed number instead of the controls.
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("confirmChannelNumber", calls.last()) }
        compose.runOnIdle { state.value = state.value.copy(numberEntry = NumberEntry("77", missing = true)) }
        compose.onNode(hasText("Нет такого канала") and hasAnyAncestor(hasTestTag("player_channel_number"))).assertIsDisplayed()
    }
}
