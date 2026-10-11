package tv.airloom.app

import android.graphics.Bitmap
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import tv.airloom.app.ui.*
import tv.airloom.core.*
import tv.airloom.playback.*
import java.io.File
import java.lang.reflect.Proxy

/** Fully synthetic UI; does not open the user's database or fetch provider streams. */
@OptIn(ExperimentalTestApi::class)
class LoadingAndPlayerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val channel = Channel("fixture", "fixture", "Тестовый канал", "https://example.invalid/live", group = "Тест")
    private fun actions(onSeek: (Long) -> Unit = {}): UiActions = Proxy.newProxyInstance(
        UiActions::class.java.classLoader, arrayOf(UiActions::class.java)) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "SyntheticActions"
            "playerSeekTo" -> { onSeek(args!!.first() as Long); null }
            else -> null
        }
    } as UiActions

    @Test fun loadingCatalogueHasDelayedSkeletonAndNoFalseEmptyState() {
        val state = mutableStateOf(UiState(loading = false, screen = Screen.GUIDE,
            settings = AppSettings(preview = false), channelLoad = ContentLoad(LoadPhase.LOADING), categoryLoad = ContentLoad(LoadPhase.LOADING)))
        compose.mainClock.autoAdvance = false
        compose.setContent { AirloomApp(state.value, actions()) {} }
        compose.mainClock.advanceTimeBy(100)
        compose.onAllNodesWithTag("skeleton_rows").assertCountEquals(0)
        compose.onNodeWithText("Список пуст").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(200)
        compose.onAllNodesWithTag("skeleton_rows").assertCountEquals(2)
        compose.runOnIdle { state.value = state.value.copy(channelLoad = ContentLoad(), categoryLoad = ContentLoad(),
            allChannels = listOf(channel), channels = listOf(channel), focusedChannel = channel) }
        compose.mainClock.autoAdvance = true
        compose.onAllNodesWithTag("skeleton_rows").assertCountEquals(0)
        compose.onNodeWithContentDescription("Тестовый канал: Нет программы").assertIsDisplayed().assertIsFocused()
    }

    @Test fun searchDoesNotShowNoResultsWhileItIsLoading() {
        compose.setContent { AirloomTheme { SearchPanel(UiState(loading = false, searchQuery = "Матч",
            searchLoad = ContentLoad(LoadPhase.LOADING)), actions(), open = true, VoiceInput { it(VoiceInputResult.Cancelled) }) } }
        compose.onNodeWithText("Ничего не найдено").assertDoesNotExist()
        compose.waitUntil(2_000) { compose.onAllNodesWithTag("skeleton_rows").fetchSemanticsNodes().size == 1 }
    }

    @Test fun draftSeekOnlyCommitsOnOkAndPauseKeepsControls() {
        val seeks = mutableListOf<Long>()
        val programme = Programme("p", "e", "fixture", "Длинная передача", 1_000_000, 2_000_000)
        compose.setContent { AirloomPlayerOverlay(UiState(loading = false, screen = Screen.PLAYER,
            settings = AppSettings(playerControlsSeconds = 1),
            player = PlaybackState(request = PlaybackRequest.Archive(channel, programme), channel = channel,
                positionMs = 100_000, durationMs = 1_000_000, isSeekable = true)), actions { seeks += it }) }
        compose.onNodeWithText("Смотреть").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        val timeline = compose.onNodeWithContentDescription("Позиция воспроизведения")
        timeline.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
        timeline.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(listOf(120_000L), seeks) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText("Смотреть").assertIsDisplayed()
    }

    @Test fun enlargedPlayerFitsLongTitlesAndLargeTextOverBrightVideo() {
        val programme = Programme("p", "e", "fixture", "Очень длинное название передачи о путешествиях по заповедным уголкам нашей планеты", 1_000_000, 4_000_000)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFFFFFFBB))) {
                AirloomPlayerOverlay(UiState(loading = false, screen = Screen.PLAYER, now = 2_000_000,
                    settings = AppSettings(textSize = 2, accent = Accent.TEAL, playerControlsSeconds = 0), playingProgramme = programme,
                    player = PlaybackState(request = PlaybackRequest.Live(channel), channel = channel, hasRenderedFrame = true, isPlaying = true)), actions())
            }
        }
        val title = compose.onNodeWithTag("player_title").getUnclippedBoundsInRoot()
        val information = compose.onNodeWithTag("player_information").getUnclippedBoundsInRoot()
        val timeline = compose.onNodeWithTag("programme_progress").getUnclippedBoundsInRoot()
        val play = compose.onNodeWithText("Пауза").getUnclippedBoundsInRoot()
        assertTrue((title.bottom - title.top).value > 28)
        assertTrue(information.bottom < timeline.top)
        assertTrue(timeline.bottom < play.top)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, "redesign-player.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun idleBackgroundReusesCacheAndNeverCapturesWhenPreviewIsOff() {
        var captures = 0
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(80, 100, 160)) }
        val service = object : FramePreviewService {
            override fun key(channel: Channel) = "synthetic"
            override fun cached(key: String) = bitmap
            override fun coolingDown(key: String) = false
            override suspend fun capture(channel: Channel, texture: TextureView): Bitmap? { captures++; return bitmap }
            override fun stop() = Unit
        }
        val state = mutableStateOf(UiState(loading = false, screen = Screen.PLAYER, focusedChannel = channel,
            settings = AppSettings(lastChannelId = channel.id, preview = true)))
        compose.setContent { CompositionLocalProvider(LocalPreviewService provides service) { AirloomPlayerOverlay(state.value, actions()) } }
        compose.onNodeWithTag("channel_frame_image").assertIsDisplayed()
        compose.onNodeWithText("Смотреть").assertIsFocused()
        compose.runOnIdle { state.value = state.value.copy(settings = state.value.settings.copy(preview = false)) }
        compose.onNodeWithTag("channel_frame_image").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, captures) }
    }
    @Test fun staleFrameStaysVisibleDuringFailedRefresh() {
        var captures = 0
        val finished = kotlinx.coroutines.CompletableDeferred<Bitmap?>()
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(80, 100, 160))
        }
        val service = object : FramePreviewService {
            override fun key(channel: Channel) = channel.id
            override fun cached(key: String) = bitmap
            override fun needsRefresh(key: String) = true
            override fun coolingDown(key: String) = false
            override suspend fun capture(channel: Channel, texture: TextureView): Bitmap? {
                captures++
                return finished.await()
            }
            override fun stop() = Unit
        }
        compose.setContent {
            CompositionLocalProvider(LocalPreviewService provides service) {
                ChannelFrame(channel, true, Modifier.fillMaxSize())
            }
        }
        compose.waitUntil(5_000) { captures == 1 }
        compose.onNodeWithTag("channel_frame_image").assertIsDisplayed()
        compose.runOnIdle { finished.complete(null) }
        compose.waitForIdle()
        compose.onNodeWithTag("channel_frame_image").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, captures) }
    }

}
