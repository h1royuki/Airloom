package tv.airloom.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import tv.airloom.app.ui.AirloomApp
import tv.airloom.app.ui.AirloomPlayerOverlay
import androidx.compose.foundation.layout.Box
import tv.airloom.core.*
import tv.airloom.playback.*
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** Entirely in-memory programme data and player: no provider requests or user preference writes. */
@OptIn(ExperimentalTestApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ProgrammeBrowserUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration by lazy { StateRestorationTester(compose) }
    private val store = ViewModelStore()
    private val base = WizardTestRepository()
    private val today = LocalDate.of(2026, 10, 4)
    private val zone = ZoneId.systemDefault()
    private var now = today.atTime(19, 42).atZone(zone).toInstant().toEpochMilli()
    private val channel = Channel("archive-channel", "fixture", "Тестовый канал", "https://example.invalid/live/index.m3u8", catchup = CatchupSpec("flussonic", days = 10))
    private val other = channel.copy(id = "other", name = "Другой канал", number = 1)
    private val playerState = MutableStateFlow(PlaybackState())
    private val playCalls = AtomicInteger()
    private var failDay = false
    private var gateDay: LocalDate? = null
    private var gate: CompletableDeferred<Unit>? = null
    private val cancellations = AtomicInteger()
    private lateinit var model: AirloomViewModel
    private val repository = object : AirloomRepository by base {
        override fun programmes(channelIds: List<String>, start: Long, end: Long): Flow<Map<String, List<Programme>>> = flow {
            // Full-day requests are unique to the new programme browser.
            val day = ProgrammeBrowserPolicy.today(start, zone)
            if (end - start >= 23 * 3600_000L) {
                if (failDay) throw IllegalStateException("Synthetic storage failure")
                if (gateDay == day) try { gate?.await() } catch (cancelled: CancellationException) { cancellations.incrementAndGet(); throw cancelled }
            }
            emitAll(base.programmes(channelIds, start, end))
        }
    }
    private fun p(id: String, day: LocalDate, hour: Int, endHour: Int = hour + 1) = Programme(id, "epg", "c", "Передача $id",
        day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(),
        day.atTime(endHour, 0).atZone(zone).toInstant().toEpochMilli(), "Описание передачи $id")
    private val current get() = p("current", today, 19, 21)
    private fun awaitDay(day: LocalDate) = compose.waitUntil(8_000) {
        model.state.value.programmeBrowser.loadedDate == day && model.state.value.programmeBrowser.load.phase == LoadPhase.READY
    }
    private fun focus(tag: String) = compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
    private fun press(tag: String, key: Key = Key.DirectionCenter) { focus(tag).performKeyInput { pressKey(key) } }
    @Before fun setup() {
        base.seedCompletedGuide(channel, StartupMode.GUIDE)
        base.replaceChannels(listOf(channel, other))
        base.settings.value = base.settings.value.copy(preview = false, logos = false, textSize = 2)
        base.testProgrammes.value = mapOf(channel.id to (listOf(p("early", today, 8), current, p("future", today, 21)) +
            (0..20).map { p("past$it", today.minusDays(1), it) } + listOf(p("older", today.minusDays(2), 10))), other.id to listOf(current))
        val playback = Proxy.newProxyInstance(PlaybackController::class.java.classLoader, arrayOf(PlaybackController::class.java)) { _, method, args ->
            when (method.name) {
                "getState" -> playerState
                "play" -> { val request = args!![0] as PlaybackRequest; playCalls.incrementAndGet(); playerState.value = PlaybackState(request = request, channel = request.channel); null }
                "stop", "release" -> { playerState.value = PlaybackState(); null }
                else -> null
            }
        } as PlaybackController
        val previews = Proxy.newProxyInstance(FramePreviewService::class.java.classLoader, arrayOf(FramePreviewService::class.java)) { _, method, _ ->
            when (method.name) { "key" -> "synthetic"; "needsRefresh", "coolingDown" -> false; else -> null }
        } as FramePreviewService
        compose.runOnUiThread {
            model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = AirloomViewModel(repository, compose.activity, AirloomRuntime(now = { now }), playback, previews) as T
            })[AirloomViewModel::class.java]
        }
        restoration.setContent { val state by model.state.collectAsState(); Box { AirloomApp(state, model) {}; AirloomPlayerOverlay(state, model) } }
        awaitDay(today)
    }
    @After fun teardown() { compose.runOnUiThread { store.clear() } }

    @Test fun currentProgressIsVisibleWhileFocusStaysInChannelsAndPreviewIs16By9() {
        compose.onNodeWithContentDescription("Тестовый канал: Передача current").assertIsFocused()
        compose.onNodeWithTag("programme_live_marker", useUnmergedTree = true).assertIsDisplayed()
        val progress = compose.onNodeWithTag("programme_live_progress", useUnmergedTree = true).assertIsDisplayed()
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(.35f, progress.current, .001f)
        compose.onNodeWithText("Смотреть", substring = false).assertDoesNotExist()
        val bounds = compose.onNodeWithTag("channel_frame").getUnclippedBoundsInRoot()
        val picker = compose.onNodeWithTag("programme_date_picker").getUnclippedBoundsInRoot()
        assertTrue(bounds.left.value < picker.left.value && bounds.right.value > picker.right.value)
        assertEquals(16f / 9f, (bounds.right.value - bounds.left.value) / (bounds.bottom.value - bounds.top.value), .02f)
        compose.onNodeWithTag("programme_day_list").performScrollToNode(hasTestTag("programme_row:future"))
        focus("programme_row:future")
        compose.onNodeWithTag("programme_live_marker", useUnmergedTree = true).assertIsDisplayed()
        File(compose.activity.cacheDir, "synthetic-unified-programme.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun searchOpensOverTheChannelsAndBackReturnsFocusToTheSelectedRow() {
        val row = compose.onNode(hasContentDescription("Тестовый канал: Передача current") and hasAnyAncestor(hasTestTag("channels_list")))
        row.assertIsFocused()
        compose.runOnUiThread { model.navigate(Screen.SEARCH) }
        compose.onNodeWithTag("search_field").assertIsFocused()
        // The channel screen stays composed under the layer and cannot take the focus: its rows are not focusable.
        val focusable = SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Focused)
        row.assertExists().assert(focusable.not())
        compose.onNodeWithTag("channel_programme_panel").assertExists()
        compose.runOnUiThread { model.search("Передача") }
        compose.waitUntil(8_000) { model.state.value.searchArchive.isNotEmpty() && model.state.value.searchLoad.phase == LoadPhase.READY }
        compose.onNodeWithTag("search_archive_section").assertIsDisplayed()
        File(compose.activity.cacheDir, "synthetic-search-layer.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("search_field").performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionDown); pressKey(Key.DirectionLeft) }
        row.assert(focusable.not())
        compose.onNode(isFocused()).assert(hasAnyAncestor(hasTestTag("search_panel")))
        compose.runOnUiThread { model.back() }
        compose.waitUntil { model.state.value.screen == Screen.GUIDE }
        row.assertIsFocused()
        compose.onNodeWithTag("search_panel").assertDoesNotExist()
    }

    @Test fun searchArchiveOpensDetailsAndStartsPlaybackThenReturnsToSearch() {
        compose.runOnUiThread { model.navigate(Screen.SEARCH); model.search("past16") }
        compose.waitUntil(8_000) { model.state.value.searchArchive.isNotEmpty() }
        press("search_archive:${channel.id}:past16")
        compose.onNodeWithText("Смотреть в архиве").assertIsDisplayed()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil { model.state.value.screen == Screen.PLAYER }
        assertEquals("past16", (playerState.value.request as PlaybackRequest.Archive).programme.id)
        compose.runOnUiThread { model.back() }
        compose.waitUntil { model.state.value.screen == Screen.SEARCH && model.state.value.searchArchive.isNotEmpty() }
        compose.onNodeWithTag("search_archive:${channel.id}:past16").assertIsDisplayed()
    }

    @Test fun calendarDpadSelectsDayThenPlayAndBackRestoreProgrammeFocus() {
        assertEquals(0, playCalls.get())
        press("programme_date_picker")
        compose.onNodeWithTag("programme_date:$today").assertIsFocused()
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        android.os.SystemClock.sleep(200)
        File(compose.activity.cacheDir, "synthetic-programme-calendar.png").outputStream().use {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("programme_date:$today").performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionCenter) }
        awaitDay(today.minusDays(1))
        compose.onNodeWithTag("programme_date_picker").assertIsFocused()
        assertEquals(0, playCalls.get())
        compose.onNodeWithTag("programme_day_list").performScrollToNode(hasTestTag("programme_row:past16"))
        focus("programme_row:past16")
        File(compose.activity.cacheDir, "synthetic-programme-history.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        press("programme_row:past16")
        compose.waitUntil { model.state.value.screen == Screen.PLAYER }
        assertEquals("past16", (playerState.value.request as PlaybackRequest.Archive).programme.id)
        compose.runOnUiThread { model.back() }
        awaitDay(today.minusDays(1))
        compose.onNodeWithTag("programme_row:past16").assertIsFocused().assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Тестовый канал: Передача current").assertIsFocused()
        assertEquals(Screen.GUIDE, model.state.value.screen)
    }
    @Test fun dismissCalendarKeepsDateAndCurrentRestoresTodayWithoutPlayback() {
        compose.runOnUiThread { model.selectProgrammeDate(today.minusDays(2)) }
        awaitDay(today.minusDays(2))
        press("programme_date_picker")
        compose.onNodeWithTag("programme_date:${today.minusDays(2)}").performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.Back) }
        compose.waitForIdle()
        compose.onNodeWithTag("programme_date_picker").assertIsFocused()
        assertEquals(today.minusDays(2), model.state.value.programmeBrowser.date)
        press("programme_current")
        awaitDay(today)
        compose.onNodeWithTag("programme_row:current").assertIsDisplayed()
        assertNull(model.state.value.programmeBrowser.date)
        assertEquals(0, playCalls.get())
    }
    @Test fun switchingChannelResetsDateButSameChannelRefreshPreservesIt() {
        compose.runOnUiThread { model.selectProgrammeDate(today.minusDays(1)) }
        awaitDay(today.minusDays(1))
        compose.runOnUiThread { base.replaceChannels(listOf(channel.copy(epgChannelId = "new-binding"), other)) }
        compose.waitUntil { model.state.value.focusedChannel?.epgChannelId == "new-binding" }
        assertEquals(today.minusDays(1), model.state.value.programmeBrowser.date)
        compose.runOnUiThread { model.focusProgramme(other, current) }
        awaitDay(today)
        assertNull(model.state.value.programmeBrowser.date)
        assertEquals(other.id, model.state.value.programmeBrowser.channelId)
        assertEquals(0, playCalls.get())
    }
    @Test fun cancelledDayCannotOverwriteNewSelectionAndErrorCanRetry() {
        compose.runOnUiThread { gateDay = today.minusDays(1); gate = CompletableDeferred(); model.selectProgrammeDate(gateDay!!) }
        compose.waitUntil { model.state.value.programmeBrowser.load.phase == LoadPhase.LOADING }
        compose.runOnUiThread { model.selectProgrammeDate(today.minusDays(2)) }
        awaitDay(today.minusDays(2))
        compose.waitUntil { cancellations.get() > 0 }
        compose.runOnUiThread { gate?.complete(Unit) }
        assertEquals(today.minusDays(2), model.state.value.programmeBrowser.loadedDate)
        compose.runOnUiThread { failDay = true; model.retryProgrammeDay() }
        compose.waitUntil { model.state.value.programmeBrowser.load.phase == LoadPhase.ERROR }
        assertEquals("older", model.state.value.programmeBrowser.programmes.single().id)
        compose.runOnUiThread { failDay = false; model.retryProgrammeDay() }
        awaitDay(today.minusDays(2))
    }
    @Test fun futureOpensDetailsAndArchiveAvailabilityIsRecheckedAtClick() {
        compose.onNodeWithTag("programme_day_list").performScrollToNode(hasTestTag("programme_row:future"))
        press("programme_row:future")
        compose.onNodeWithText("Передача ещё не началась").assertExists()
        assertEquals(0, playCalls.get())
        compose.runOnUiThread { model.dismissDetail(); now += 11 * 86400_000L; model.playBrowserProgramme(current) }
        compose.waitForIdle()
        assertEquals(0, playCalls.get())
        assertEquals(Screen.GUIDE, model.state.value.screen)
    }

    @Test fun calendarMonthNavigationBoundsAndLightTheme() {
        compose.runOnUiThread { base.settings.value = base.settings.value.copy(theme = ThemeMode.LIGHT) }
        press("programme_date_picker")
        compose.onNodeWithTag("programme_date:${today.plusDays(1)}").assertIsNotEnabled()
        compose.onNodeWithTag("programme_month_next").assertIsNotEnabled()
        press("programme_month_previous")
        compose.onNodeWithTag("programme_date:2026-09-23").assertIsNotEnabled()
        compose.onNodeWithTag("programme_month_previous").assertIsNotEnabled()
        press("programme_date:2026-09-30")
        awaitDay(LocalDate.of(2026, 9, 30))
        compose.onNodeWithText("Нет телепрограммы за эту дату").assertIsDisplayed()
        press("programme_next_day")
        awaitDay(LocalDate.of(2026, 10, 1))
        compose.onNodeWithTag("programme_date_picker").assertIsFocused()
        assertEquals(0, playCalls.get())
    }
    @Test fun midnightSpanningProgrammeIsIncludedAndMalformedIntervalExcluded() {
        val start = ProgrammeBrowserPolicy.window(today, zone).first
        val crossing = Programme("crossing", "epg", "c", "Ночная передача", start - 3_600_000, start + 3_600_000)
        val invalid = crossing.copy(id = "invalid", start = now + 1000, end = now - 1000)
        compose.runOnUiThread { base.testProgrammes.value = mapOf(channel.id to listOf(crossing, invalid, current)) }
        compose.waitUntil { model.state.value.programmeBrowser.programmes.size == 2 }
        assertTrue(model.state.value.programmeBrowser.programmes.any { it.id == "crossing" })
        assertFalse(model.state.value.programmeBrowser.programmes.any { it.id == "invalid" })
        compose.runOnUiThread { model.selectProgrammeDate(today.minusDays(1)) }
        awaitDay(today.minusDays(1))
        assertEquals("crossing", model.state.value.programmeBrowser.programmes.single().id)
    }

    @Test fun saveableRestorationKeepsDateProgrammeAndFocus() {
        compose.runOnUiThread { model.selectProgrammeDate(today.minusDays(1)) }
        awaitDay(today.minusDays(1))
        compose.onNodeWithTag("programme_day_list").performScrollToNode(hasTestTag("programme_row:past12"))
        focus("programme_row:past12")
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(today.minusDays(1), model.state.value.programmeBrowser.date)
        compose.onNodeWithTag("programme_row:past12").assertIsFocused().assertIsDisplayed()
        assertEquals(0, playCalls.get())
    }
    @Test fun currentCanRestartAndMissingArchiveOpensLiveWithoutPastDates() {
        press("programme_row:current")
        compose.waitUntil { model.state.value.screen == Screen.PLAYER }
        assertEquals("current", (playerState.value.request as PlaybackRequest.Archive).programme.id)
        compose.runOnUiThread { model.back(); base.replaceChannels(listOf(channel.copy(catchup = CatchupSpec()), other)) }
        compose.waitUntil { model.state.value.focusedChannel?.catchup?.days == 0 }
        awaitDay(today)
        compose.onNodeWithTag("programme_previous_day").assertDoesNotExist()
        compose.onNodeWithTag("programme_date_picker").assertDoesNotExist()
        compose.onNodeWithTag("programme_row:early").assertDoesNotExist()
        press("programme_row:current")
        assertEquals(2, playCalls.get())
        assertTrue(playerState.value.request is PlaybackRequest.Live)
    }
    @Test fun leftFromTheDateRowReturnsToTheSelectedChannelNotTheLevelRow() {
        // Enough rows for the list to reach the height of the date row.
        val filler = (2..12).map { channel.copy(id = "filler$it", name = "Канал $it", number = it) }
        compose.runOnUiThread { base.replaceChannels(listOf(channel, other) + filler) }
        compose.waitUntil { model.state.value.channels.size == 13 }
        val row = compose.onNode(hasContentDescription("Тестовый канал: Передача current") and hasAnyAncestor(hasTestTag("channels_list")))
        row.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("programme_date_picker").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithTag("programme_previous_day").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        row.assertIsFocused()
        compose.runOnIdle { assertEquals(channel.id, model.state.value.focusedChannel?.id) }
    }
    @Test fun leavingThePlayerReturnsToTheChannelWatchedLast() {
        val row = compose.onNode(hasContentDescription("Тестовый канал: Передача current") and hasAnyAncestor(hasTestTag("channels_list")))
        row.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil { model.state.value.screen == Screen.PLAYER }
        // The channels screen has left the composition, as it has by the time anyone switches the channel.
        compose.waitUntil { compose.onAllNodesWithTag("channels_list").fetchSemanticsNodes().isEmpty() }
        compose.runOnUiThread { model.nextChannel(1) }
        compose.waitUntil { playerState.value.channel?.id == other.id }
        compose.runOnUiThread { model.back() }
        compose.waitUntil { model.state.value.screen == Screen.GUIDE }
        compose.waitUntil { compose.onAllNodes(hasContentDescription("Другой канал: Передача current") and hasAnyAncestor(hasTestTag("channels_list")) and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(other.id, model.state.value.focusedChannel?.id) }
    }
    @Test fun typedNumberOpensItsChannelAndAnUnknownOneIsReported() {
        val row = compose.onNode(hasContentDescription("Тестовый канал: Передача current") and hasAnyAncestor(hasTestTag("channels_list")))
        row.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil { model.state.value.screen == Screen.PLAYER }
        compose.runOnUiThread { model.channelDigit(1) }
        compose.runOnIdle { assertEquals(NumberEntry("1", "Другой канал"), model.state.value.numberEntry) }
        compose.onNodeWithTag("player_channel_number").assertIsDisplayed()
        compose.runOnUiThread { model.confirmChannelNumber() }
        compose.waitUntil { playerState.value.channel?.id == other.id }
        compose.runOnIdle { assertNull(model.state.value.numberEntry) }
        // An unknown number is confirmed by the pause after its last digit, says so and leaves by itself; the channel stays.
        compose.runOnUiThread { model.channelDigit(7); model.channelDigit(7) }
        compose.waitUntil(5_000) { model.state.value.numberEntry == NumberEntry("77", missing = true) }
        compose.onNodeWithText("Нет такого канала").assertIsDisplayed()
        compose.waitUntil(5_000) { model.state.value.numberEntry == null }
        assertEquals(other.id, playerState.value.channel?.id)
    }
    @Test fun channelKeysEnterTheListFromAChannelOutsideIt() {
        val first = channel.copy(id = "sport1", name = "Спорт 1", group = "Спорт", number = 5)
        val second = first.copy(id = "sport2", name = "Спорт 2", number = 6)
        compose.runOnUiThread { base.replaceChannels(listOf(channel, other, first, second)) }
        compose.waitUntil { model.state.value.categories.any { it.name == "Спорт" } }
        compose.runOnUiThread { model.selectCategory(model.state.value.categories.first { it.name == "Спорт" }) }
        compose.waitUntil { model.state.value.channels.map { it.id } == listOf("sport1", "sport2") }
        compose.runOnUiThread { model.play(channel); model.nextChannel(1) }
        compose.waitUntil { playerState.value.channel?.id == "sport1" }
    }
    @Test fun returningFromTheBackgroundKeepsThePlayerAndContinuesTheProgramme() {
        press("programme_row:early")
        compose.waitUntil { model.state.value.screen == Screen.PLAYER }
        val request = playerState.value.request
        assertEquals("early", (request as PlaybackRequest.Archive).programme.id)
        compose.runOnUiThread { model.background() }
        compose.runOnIdle { assertEquals(Screen.PLAYER, model.state.value.screen); assertNull(model.state.value.player.request) }
        assertNull(playerState.value.request)
        // The idle player names the channel and waits for an explicit «Смотреть».
        compose.waitUntil(2_000) { compose.onAllNodes(hasText("Смотреть") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Смотреть").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil { playerState.value.request == request }
    }
    @Test fun epgReplacementPreservesSelectionOrChoosesNearestAndEmptyDayIsSafe() {
        compose.runOnUiThread { model.selectProgrammeDate(today.minusDays(1)) }
        awaitDay(today.minusDays(1))
        compose.onNodeWithTag("programme_day_list").performScrollToNode(hasTestTag("programme_row:past10"))
        focus("programme_row:past10")
        compose.runOnUiThread { base.testProgrammes.value = base.testProgrammes.value.mapValues { (_, values) -> values.filterNot { it.id == "past10" } } }
        compose.waitUntil { model.state.value.programmeBrowser.selected?.id == "past9" }
        compose.onNodeWithTag("programme_row:past9").assertIsFocused()
        compose.runOnUiThread { model.selectProgrammeDate(today.minusDays(3)) }
        awaitDay(today.minusDays(3))
        compose.onNodeWithText("Нет телепрограммы за эту дату").assertIsDisplayed()
        assertEquals(0, playCalls.get())
    }
}
