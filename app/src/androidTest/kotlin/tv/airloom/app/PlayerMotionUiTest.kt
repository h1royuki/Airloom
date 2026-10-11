package tv.airloom.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tv.airloom.app.ui.AirloomPlayerOverlay
import tv.airloom.app.ui.AirloomTheme
import tv.airloom.app.ui.ProgrammeTimeline
import tv.airloom.app.ui.timeLabel
import tv.airloom.core.AppSettings
import tv.airloom.core.Channel
import tv.airloom.core.PlaybackRequest
import tv.airloom.core.Programme
import tv.airloom.playback.PlaybackState
import java.io.File
import java.lang.reflect.Proxy

/** Synthetic UI only; does not load streams or change provider data. */
@OptIn(ExperimentalTestApi::class)
class PlayerMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val channel = Channel("test", "playlist", "Тестовый канал", "https://example.invalid/live")
    private val programme = Programme("p", "epg", channel.id, "Передача для проверки", 1_000_000, 4_000_000)
    private val actions = Proxy.newProxyInstance(UiActions::class.java.classLoader, arrayOf(UiActions::class.java)) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "MotionActions"
            else -> null
        }
    } as UiActions

    @Test fun archiveTimeFollowsPlaybackAndIntervalAppearsOnlyAtEdges() {
        compose.setContent {
            AirloomPlayerOverlay(UiState(loading = false, screen = Screen.PLAYER, now = 9_000_000,
                settings = AppSettings(textSize = 2, playerControlsSeconds = 0),
                player = PlaybackState(request = PlaybackRequest.Archive(channel, programme), channel = channel,
                    durationMs = 3_000_000, positionMs = 900_000, isSeekable = true)), actions)
        }
        compose.onNodeWithTag("programme_position_time").assertTextEquals(timeLabel(programme.start + 900_000))
        compose.onNodeWithTag("programme_start_time").assertTextEquals(timeLabel(programme.start))
        compose.onNodeWithTag("programme_end_time").assertTextEquals(timeLabel(programme.end))
        compose.onNodeWithText(channel.name).assertIsDisplayed()
        compose.onNodeWithText("Архив").assertIsDisplayed()
        compose.onAllNodesWithText(timeLabel(programme.start)).assertCountEquals(1)
        compose.onAllNodesWithText(timeLabel(programme.end)).assertCountEquals(1)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, "synthetic-player.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun positionDotAndLabelStayInsideAtEdgesWithLargeText() {
        val fraction = mutableStateOf(0f)
        compose.setContent { AirloomTheme(textScale = 1.15f) { ProgrammeTimeline(fraction.value, "10:00", "11:00", "10:30") } }
        for (value in listOf(0f, .5f, 1f)) {
            compose.runOnIdle { fraction.value = value }
            compose.waitForIdle()
            val root = compose.onRoot().getUnclippedBoundsInRoot()
            val dot = compose.onNodeWithTag("programme_progress_dot").getUnclippedBoundsInRoot()
            val label = compose.onNodeWithTag("programme_position_time").getUnclippedBoundsInRoot()
            val start = compose.onNodeWithTag("programme_start_time").getUnclippedBoundsInRoot()
            assertTrue(dot.left >= root.left && dot.right <= root.right)
            assertTrue(label.left >= root.left && label.right <= root.right)
            val track = compose.onNodeWithTag("programme_progress").getUnclippedBoundsInRoot()
            val end = compose.onNodeWithTag("programme_end_time").getUnclippedBoundsInRoot()
            // The time rides above the thumb; start and end flank the track on its own line.
            assertTrue(label.bottom <= dot.top)
            assertTrue(start.right < track.left && end.left > track.right)
            assertTrue(start.bottom > dot.top && start.top < dot.bottom)
            val expected = track.left.value + (track.right - track.left).value * value
            assertEquals(expected, (dot.left + (dot.right - dot.left) / 2).value, 1f)
        }
    }

    @Test fun liveWithoutEpgHasNoTimelineEvenWhenTheStreamIsSeekable() {
        val state = mutableStateOf(UiState(loading = false, screen = Screen.PLAYER,
            settings = AppSettings(playerControlsSeconds = 0),
            player = PlaybackState(request = PlaybackRequest.Live(channel), channel = channel,
                isSeekable = true, durationMs = 120_000, positionMs = 30_000)))
        compose.setContent { AirloomPlayerOverlay(state.value, actions) }
        compose.onNodeWithText("Смотреть").assertIsFocused()
        compose.onNodeWithTag("programme_progress").assertDoesNotExist()
        compose.onNodeWithTag("programme_position_time").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(playingProgramme = programme, now = programme.start + 900_000) }
        compose.onNodeWithTag("programme_progress").assertIsDisplayed()
    }

    @Test fun hidingControlsImmediatelyReleasesFocusAndCanReopen() {
        val state = mutableStateOf(UiState(loading = false, screen = Screen.PLAYER,
            settings = AppSettings(playerControlsSeconds = 0),
            player = PlaybackState(request = PlaybackRequest.Live(channel), channel = channel)))
        compose.setContent { AirloomPlayerOverlay(state.value, actions) }
        compose.onNodeWithText("Смотреть").assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(playerControls = false) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Смотреть").assert(isFocused().not()).assertIsNotEnabled()
        compose.onAllNodes(isFocused()).assertCountEquals(1)
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithText("Смотреть").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(playerControls = true) }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Смотреть").assertIsFocused()
    }

    @Test fun closingChannelPanelReleasesFocusDuringExit() {
        val state = mutableStateOf(UiState(loading = false, screen = Screen.PLAYER,
            playerControls = false, quickChannels = true, allChannels = listOf(channel), channels = listOf(channel),
            player = PlaybackState(request = PlaybackRequest.Live(channel), channel = channel)))
        compose.setContent { AirloomPlayerOverlay(state.value, actions) }
        val row = compose.onNodeWithContentDescription("Тестовый канал: Нет программы, в эфире")
        row.assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(quickChannels = false) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        row.assert(isFocused().not()).assertIsNotEnabled()
        compose.onAllNodes(isFocused()).assertCountEquals(1)
        compose.mainClock.advanceTimeBy(250)
        row.assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun controlsAutoHideAndChannelSwitchResetsProgress() {
        val state = mutableStateOf(UiState(loading = false, screen = Screen.PLAYER, now = programme.start + 900_000,
            settings = AppSettings(playerControlsSeconds = 1), playingProgramme = programme,
            player = PlaybackState(request = PlaybackRequest.Live(channel), channel = channel, isPlaying = true)))
        var hidden = false
        val updatingActions = Proxy.newProxyInstance(UiActions::class.java.classLoader, arrayOf(UiActions::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "UpdatingMotionActions"
                "showPlayerControls" -> { hidden = args?.firstOrNull() == false; state.value = state.value.copy(playerControls = !hidden); null }
                else -> null
            }
        } as UiActions
        compose.setContent { AirloomPlayerOverlay(state.value, updatingActions) }
        compose.onNodeWithText("Пауза").assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            val next = channel.copy(id = "next")
            state.value = state.value.copy(playingProgramme = programme.copy(id = "next-p", start = state.value.now),
                player = state.value.player.copy(channel = next, request = PlaybackRequest.Live(next)))
        }
        compose.mainClock.advanceTimeByFrame()
        val dot = compose.onNodeWithTag("programme_progress_dot").getUnclippedBoundsInRoot()
        val track = compose.onNodeWithTag("programme_progress").getUnclippedBoundsInRoot()
        assertEquals(track.left.value, (dot.left + (dot.right - dot.left) / 2).value, 1f)
        compose.mainClock.advanceTimeBy(1_500)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(2_000) { hidden }
        compose.onNodeWithText("Пауза").assertDoesNotExist()
    }
}
