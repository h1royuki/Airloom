package tv.airloom.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import tv.airloom.app.ui.*
import tv.airloom.core.*

@OptIn(ExperimentalTestApi::class)
class SearchUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val channel = Channel("a", "test", "Матч", "https://example.invalid/live/index.m3u8")
    private val programme = Programme("p", "epg", "one", "Футбол", 1_000_000, 2_000_000)
    private fun actions(onSearch: (String) -> Unit = {}, onActivate: (Channel, Programme?) -> Unit = { _, _ -> }, onChannel: (String, Channel, Boolean) -> Unit = { _, _, _ -> }) =
        Proxy.newProxyInstance(UiActions::class.java.classLoader, arrayOf(UiActions::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy); "equals" -> proxy === args?.firstOrNull(); "toString" -> "SearchActions"
                "search" -> { onSearch(args!![0] as String); null }
                "activate" -> { onActivate(args!![0] as Channel, args[1] as Programme?); null }
                "setFavourite", "setHidden" -> { onChannel(method.name, args!![0] as Channel, args[1] as Boolean); null }
                else -> null
            }
        } as UiActions
    private fun base() = UiState(loading = false, screen = Screen.SEARCH, searchQuery = "Матч", settings = AppSettings(logos = false, preview = false))
    private val cancelVoice = VoiceInput { it(VoiceInputResult.Cancelled) }
    private fun openMicrophone() {
        compose.onNodeWithTag("search_microphone").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("search_microphone").performKeyInput { pressKey(Key.DirectionCenter) }
    }

    @Test fun cardHoldsFieldThenChannelsOnAirAndArchiveSectionsAndRowsActivate() {
        var activated: Pair<Channel, Programme?>? = null
        val ui = actions(onActivate = { c,p -> activated = c to p })
        val onAir = channel.copy(id = "b", name = "Спорт")
        compose.setContent { AirloomTheme(textScale = 1.15f) { SearchPanel(base().copy(
            searchLive = listOf(SearchLiveResult(channel, programme), SearchLiveResult(onAir, programme.copy(id = "live", title = "Матч дня"))),
            searchArchive = listOf(channel to programme.copy(title = "Матч вчера"))), ui, open = true, cancelVoice) } }
        // The layer opens on the query field; the card covers the rail and the channel list and stops 12 dp short of the stage.
        compose.onNodeWithTag("search_field").assertIsFocused()
        val card = compose.onNodeWithTag("search_panel").getUnclippedBoundsInRoot()
        assertEquals(428.dp, card.right - card.left)
        compose.onNodeWithTag("search_count", useUnmergedTree = true).assertTextEquals("3")
        val named = compose.onNodeWithText("Каналы · 1").assertIsDisplayed().getUnclippedBoundsInRoot()
        val first = compose.onNodeWithTag("search_live:a").assertIsDisplayed().getUnclippedBoundsInRoot()
        val air = compose.onNodeWithText("Сейчас в эфире · 1").assertIsDisplayed().getUnclippedBoundsInRoot()
        val second = compose.onNodeWithTag("search_live:b").assertIsDisplayed().getUnclippedBoundsInRoot()
        val section = compose.onNodeWithTag("search_archive_section").assertTextEquals("В архиве · 1").getUnclippedBoundsInRoot()
        val archive = compose.onNodeWithTag("search_archive:a:p").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(named.bottom <= first.top && first.bottom <= air.top && air.bottom <= second.top && second.bottom <= section.top && section.bottom <= archive.top)
        // Rows sit inside the card with the drawer's margin: 12 dp of inset and 10 dp of padding.
        assertTrue(first.left >= 22.dp && first.right <= 428.dp)
        File(compose.activity.cacheDir, "synthetic-search.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("search_live:a").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("search_live:a").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(channel, activated?.first); assertNull(activated?.second) }
        compose.onNodeWithTag("search_archive:a:p").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("search_archive:a:p").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("Матч вчера", activated?.second?.title) }
    }
    @Test fun sectionsAppearOnlyForTheirResultsAndNoEpgFallback() {
        val state = mutableStateOf(base().copy(searchLive = listOf(SearchLiveResult(channel))))
        compose.setContent { AirloomTheme { SearchPanel(state.value, actions(), open = true, cancelVoice) } }
        compose.onNodeWithText("Каналы · 1").assertIsDisplayed()
        compose.onNodeWithTag("search_archive_section").assertDoesNotExist()
        compose.onNodeWithText("Нет программы", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { state.value = base().copy(searchArchive = listOf(channel to programme)) }
        compose.onNodeWithText("Каналы · 1").assertDoesNotExist()
        compose.onNodeWithTag("search_archive_section").assertIsDisplayed()
        compose.onNodeWithTag("search_archive:a:p").assertIsDisplayed()
        compose.runOnIdle { state.value = base() }
        compose.onNodeWithText("Ничего не найдено").assertIsDisplayed()
    }
    @Test fun emptyQueryOffersRecentChannelsOrAPrompt() {
        val state = mutableStateOf(base().copy(searchQuery = "", searchLive = listOf(SearchLiveResult(channel, programme))))
        compose.setContent { AirloomTheme { SearchPanel(state.value, actions(), open = true, cancelVoice) } }
        compose.onNodeWithText("Последние каналы").assertIsDisplayed()
        compose.onNodeWithTag("search_live:a").assertIsDisplayed()
        compose.onNodeWithTag("search_count", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { state.value = base().copy(searchQuery = "") }
        compose.onNodeWithText("Последние каналы").assertDoesNotExist()
        compose.onNodeWithText("Что вас интересует?").assertIsDisplayed()
    }
    @Test fun channelMatchesStayWhileTheProgrammeSearchRuns() {
        compose.setContent { AirloomTheme { SearchPanel(base().copy(searchLive = listOf(SearchLiveResult(channel)),
            searchLoad = ContentLoad(LoadPhase.LOADING)), actions(), open = true, cancelVoice) } }
        compose.onNodeWithTag("search_live:a").assertIsDisplayed()
        compose.onNodeWithText("Нет программы", useUnmergedTree = true).assertDoesNotExist()
        compose.waitUntil(2_000) { compose.onAllNodesWithText("Ищем в телепрограмме").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("skeleton_rows").assertCountEquals(0)
    }
    @Test fun longResultsScrollWithRemoteAndLargeText() {
        val live = (0..34).map { i -> SearchLiveResult(channel.copy(id = "$i", name = "Очень длинное название телевизионного канала $i"), programme.copy(title = "Очень длинное название текущей передачи $i")) }
        compose.setContent { AirloomTheme(textScale = 1.15f) { SearchPanel(base().copy(searchQuery = "Очень", searchLive = live, searchArchive = listOf(channel to programme.copy(title = "Очень длинное название архивной передачи"))), actions(), open = true, cancelVoice) } }
        compose.onNodeWithTag("search_results").performScrollToNode(hasTestTag("search_live:30"))
        compose.onNodeWithTag("search_live:30").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("search_live:30").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("search_live:31").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithTag("search_results").performScrollToNode(hasTestTag("search_archive:a:p"))
        compose.onNodeWithTag("search_archive:a:p").assertIsDisplayed()
        compose.onNodeWithTag("search_archive_section").assertIsDisplayed()
    }

    @Test fun longPressOpensTheChannelListPopupAndHidingMovesFocusToAnotherChannel() {
        val calls = mutableListOf<String>()
        val ui = actions(onChannel = { name, target, value -> calls += "$name:${target.id}:$value" })
        val other = channel.copy(id = "b", name = "Матч ТВ")
        compose.setContent { AirloomTheme { SearchPanel(base().copy(searchLive = listOf(SearchLiveResult(channel, programme), SearchLiveResult(other, programme)),
            searchArchive = listOf(channel to programme.copy(title = "Матч вчера"))), ui, open = true, cancelVoice) } }
        val row = compose.onNodeWithTag("search_live:a")
        row.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        row.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        // The same popup as in the channel list, without the actions that belong to a list: pinning and moving.
        compose.onNodeWithTag("row_actions_popup").assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрепить").assertDoesNotExist()
        compose.onNodeWithContentDescription("Перетянуть").assertDoesNotExist()
        compose.onNodeWithContentDescription("В избранное").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("row_actions_popup").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("setFavourite:a:true"), calls) }
        row.assertIsFocused()
        // «Назад» closes the popup and leaves the focus on its row.
        row.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithContentDescription("В избранное").performKeyInput { pressKey(Key.Back) }
        compose.onNodeWithTag("row_actions_popup").assertDoesNotExist()
        row.assertIsFocused()
        // Hiding: the focus leaves for a row of another channel, past the hidden channel's archive row too.
        row.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithContentDescription("Скрыть").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithContentDescription("Скрыть").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("row_actions_popup").assertDoesNotExist()
        compose.onNodeWithTag("search_live:b").assertIsFocused()
        compose.runOnIdle { assertEquals("setHidden:a:true", calls.last()) }
        // An archive row is a programme, not a channel: it has no channel actions.
        compose.onNodeWithTag("search_archive:a:p").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }

    @Test fun arrowsStayInsideTheCardAndReachMicrophoneAndResults() {
        compose.setContent { AirloomTheme { SearchPanel(base().copy(searchLive = listOf(SearchLiveResult(channel, programme))), actions(), open = true, cancelVoice) } }
        val field = compose.onNodeWithTag("search_field")
        // There is nothing to the left of the card and nothing above the field.
        field.assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionUp) }
        field.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("search_microphone").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("search_microphone").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        field.assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        val row = compose.onNodeWithTag("search_live:a")
        row.assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionRight); pressKey(Key.DirectionDown) }
        row.assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("search_live:a").assertIsNotFocused()
    }

    @Test fun voiceResultReplacesQueryAndRestoresMicrophoneFocus() {
        val state = mutableStateOf(base())
        val ui = actions(onSearch = { state.value = state.value.copy(searchQuery = it) })
        compose.setContent { AirloomTheme { SearchPanel(state.value, ui, open = true, VoiceInput { it(VoiceInputResult.Recognized("  футбол  ")) }) } }
        openMicrophone()
        compose.runOnIdle { assertEquals("футбол", state.value.searchQuery) }
        compose.onNodeWithTag("search_microphone").assertIsFocused()
    }
    @Test fun cancelFailureAndLaunchErrorPreserveQueryAndFocus() {
        var searched = false
        var result: VoiceInputResult = VoiceInputResult.Cancelled
        var throwError = false
        val ui = actions(onSearch = { searched = true })
        compose.setContent { AirloomTheme { SearchPanel(base(), ui, open = true, VoiceInput { if (throwError) throw IllegalStateException("Synthetic failure") else it(result) }) } }
        openMicrophone()
        compose.onNodeWithTag("search_microphone").assertIsFocused()
        compose.runOnIdle { assertFalse(searched); result = VoiceInputResult.Failure("Голосовой ввод недоступен на этом устройстве.") }
        openMicrophone()
        compose.onNodeWithText("Голосовой ввод недоступен на этом устройстве.").assertIsDisplayed()
        compose.onNodeWithTag("search_microphone").assertIsFocused()
        compose.runOnIdle { assertFalse(searched); throwError = true }
        openMicrophone()
        compose.onNodeWithText("Не удалось открыть голосовой ввод.").assertIsDisplayed()
        compose.runOnIdle { assertFalse(searched) }
        compose.onNodeWithTag("search_microphone").assertIsFocused()
    }
    @Test fun lateVoiceResultAfterLeavingIsIgnored() {
        val show = mutableStateOf(true)
        var searched = false
        var callback: ((VoiceInputResult) -> Unit)? = null
        val ui = actions(onSearch = { searched = true })
        compose.setContent { AirloomTheme { if (show.value) SearchPanel(base(), ui, open = true, VoiceInput { callback = it }) } }
        openMicrophone()
        compose.runOnIdle { show.value = false }
        compose.waitForIdle()
        compose.runOnIdle { callback!!(VoiceInputResult.Recognized("ignored")); assertFalse(searched) }
    }
    @Test fun speechContractHandlesCancellationEmptyAndFirstNonblankResult() {
        val intent = speechIntent()
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals("ru-RU", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(VoiceInputResult.Cancelled, speechResult(Activity.RESULT_CANCELED, null))
        assertTrue(speechResult(Activity.RESULT_OK, null) is VoiceInputResult.Failure)
        val data = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(" ", "  Матч  ", "другой"))
        assertEquals(VoiceInputResult.Recognized("Матч"), speechResult(Activity.RESULT_OK, data))
    }
}
