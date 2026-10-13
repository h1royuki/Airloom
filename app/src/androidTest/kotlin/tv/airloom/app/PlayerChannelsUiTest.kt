package tv.airloom.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.mutableStateOf
import java.io.File
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import tv.airloom.app.ui.AirloomPlayerOverlay
import tv.airloom.app.ui.AirloomTheme
import tv.airloom.app.ui.PlayerChannelsPanel
import tv.airloom.core.Category
import tv.airloom.core.Channel
import tv.airloom.core.ChannelSelection
import tv.airloom.core.PlaybackRequest
import tv.airloom.core.Programme
import tv.airloom.playback.PlaybackState

@OptIn(ExperimentalTestApi::class)
class PlayerChannelsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val played = AtomicReference<String?>(null)
    private val opened = AtomicReference<Boolean?>(null)
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val actions = Proxy.newProxyInstance(UiActions::class.java.classLoader, arrayOf(UiActions::class.java)) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "PlayerChannelsActions"
            "play" -> { played.set((args?.firstOrNull() as Channel).id); null }
            "showQuickChannels" -> { opened.set(args?.firstOrNull() as Boolean); null }
            "nextChannel" -> { calls += "next:${args!![0]}"; null }
            "selectCategory" -> { calls += "select:" + ((args?.firstOrNull() as? Category)?.name ?: args?.firstOrNull()); null }
            "setFavourite", "setHidden" -> { calls += "${method.name}:${(args!![0] as Channel).id}:${args[1]}"; null }
            else -> null
        }
    } as UiActions

    private val live = Channel("live", "playlist", "Мир ТВ", "https://example.invalid/live.m3u8")
    private val news = Channel("news", "playlist", "Город 24", "https://example.invalid/news.m3u8", favourite = true)
    private val now = 2_000_000L
    private val liveRow = "Мир ТВ: В сердце природы, в эфире"
    private val newsRow = "Город 24: Новости дня"
    private val state = UiState(loading = false, screen = Screen.PLAYER, now = now,
        allChannels = listOf(live, news), channels = listOf(live, news),
        categories = listOf(Category("playlist", "Спорт", 2), Category("playlist", "Кино", 1, hidden = true)),
        programmes = mapOf(
            live.id to listOf(Programme("p1", "epg", live.id, "В сердце природы", now - 100_000, now + 100_000)),
            news.id to listOf(Programme("p2", "epg", news.id, "Новости дня", now - 100_000, now + 100_000)),
        ),
        player = PlaybackState(request = PlaybackRequest.Live(live), channel = live, isPlaying = true))

    private fun awaitFocus() = compose.waitUntil(2_000) { compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitFocused(description: String) = compose.waitUntil(2_000) {
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().any { it.config.getOrElse(SemanticsProperties.Focused) { false } }
    }

    @Test fun leftCardListsCurrentSectionAndSeparatesPlayingFromFocus() {
        compose.setContent { AirloomTheme { PlayerChannelsPanel(state, actions) } }
        val panel = compose.onNodeWithTag("player_channels_panel").assertIsDisplayed().getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue(kotlin.math.abs((panel.left - root.left).value) < 1f)
        org.junit.Assert.assertTrue(kotlin.math.abs((panel.right - panel.left).value - 352f) < 1f)
        // The header names the section of the main screen and counts its channels.
        compose.onNodeWithTag("player_channels_header").assert(hasAnyDescendant(hasText("Все каналы")))
        compose.onNodeWithText("Разделы").assertDoesNotExist()
        awaitFocus()
        compose.onNodeWithContentDescription(liveRow).assertIsFocused()
        compose.onNodeWithContentDescription(liveRow).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(false, opened.get()); assertNull(played.get()) }
        val other = compose.onNodeWithContentDescription(newsRow)
        other.assertExists().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        other.assertIsFocused()
        compose.onNodeWithContentDescription(liveRow).assertExists()
        // The favourite among them carries its mark.
        compose.onAllNodesWithContentDescription("В избранном", useUnmergedTree = true).assertCountEquals(1)
        other.performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals("news", played.get()) }
    }

    @Test fun channelKeysStepThroughTheListOverTheOpenCard() {
        compose.setContent { AirloomPlayerOverlay(state.copy(quickChannels = true, playerControls = false), actions) }
        awaitFocused(liveRow)
        compose.onNodeWithContentDescription(liveRow).performKeyInput { pressKey(Key.ChannelUp); pressKey(Key.ChannelDown) }
        compose.runOnIdle { assertEquals(listOf("next:1", "next:-1"), calls.toList()) }
    }

    @Test fun listFollowsTheSectionAndIgnoresTheRestOfTheCatalogue() {
        val elsewhere = Channel("film", "playlist", "Кино Хит", "https://example.invalid/film.m3u8")
        compose.setContent { AirloomTheme { PlayerChannelsPanel(state.copy(allChannels = listOf(live, news, elsewhere),
            selection = ChannelSelection.Category("playlist", "Спорт")), actions) } }
        awaitFocus()
        compose.onNodeWithTag("player_channels_header").assert(hasAnyDescendant(hasText("Спорт")))
        compose.onNodeWithContentDescription(liveRow).assertIsFocused()
        compose.onNodeWithContentDescription(newsRow).assertExists()
        compose.onNodeWithContentDescription("Кино Хит: Нет программы").assertDoesNotExist()
    }

    @Test fun leftOpensSectionsOnTheCurrentOneAndOkChoosesAnother() {
        compose.setContent { AirloomTheme { PlayerChannelsPanel(state, actions) } }
        awaitFocus()
        compose.onNodeWithContentDescription(liveRow).assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitUntil(2_000) { compose.onAllNodesWithTag("player_sections_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Разделы").assertIsDisplayed()
        compose.onNodeWithContentDescription(liveRow).assertDoesNotExist()
        // Rail order, without hidden categories.
        compose.onNodeWithText("Избранное").assertIsDisplayed()
        compose.onNodeWithText("Последние").assertIsDisplayed()
        compose.onNodeWithText("Спорт").assertIsDisplayed()
        compose.onNodeWithText("Кино").assertDoesNotExist()
        compose.waitUntil(2_000) { compose.onAllNodes(hasText("Все каналы") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Все каналы").assertIsSelected().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("Все каналы").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Последние").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Избранное").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(listOf("select:Избранное"), calls) }
        // The channels come back once the section's list has arrived; here it never changes, so after the timeout.
        awaitFocused(liveRow)
        compose.onNodeWithTag("player_sections_list").assertDoesNotExist()
        compose.runOnIdle { assertNull(opened.get()); assertNull(played.get()) }
    }

    @Test fun chosenSectionShowsItsChannelsWhenTheyArrive() {
        val current = mutableStateOf(state)
        val sport = Channel("sport", "playlist", "Арена", "https://example.invalid/sport.m3u8")
        compose.setContent { AirloomTheme { PlayerChannelsPanel(current.value, actions) } }
        awaitFocus()
        compose.onNodeWithContentDescription(liveRow).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitUntil(2_000) { compose.onAllNodes(hasText("Все каналы") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Все каналы").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Спорт").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals(listOf("select:Спорт"), calls)
            current.value = state.copy(selection = ChannelSelection.Category("playlist", "Спорт"), channels = listOf(sport, news))
        }
        // The playing channel is not in this section: the focus takes its first row.
        awaitFocused("Арена: Нет программы")
        compose.onNodeWithTag("player_channels_header").assert(hasAnyDescendant(hasText("Спорт")))
        compose.onNodeWithContentDescription(liveRow).assertDoesNotExist()
    }

    @Test fun rightAndBackLeaveSectionsWithoutChoosingAndRestoreTheRow() {
        compose.setContent { AirloomTheme { PlayerChannelsPanel(state, actions) } }
        awaitFocus()
        val other = compose.onNodeWithContentDescription(newsRow)
        other.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        other.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        other.assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitUntil(2_000) { compose.onAllNodes(hasText("Все каналы") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Все каналы").performKeyInput { pressKey(Key.DirectionRight) }
        awaitFocused(newsRow)
        compose.onNodeWithContentDescription(newsRow).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitUntil(2_000) { compose.onAllNodes(hasText("Все каналы") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Все каналы").performKeyInput { pressKey(Key.Back) }
        awaitFocused(newsRow)
        compose.runOnIdle { assertNull(opened.get()); assertEquals(emptyList<String>(), calls.toList()) }
        compose.onNodeWithContentDescription(newsRow).performKeyInput { pressKey(Key.Back) }
        compose.runOnIdle { assertEquals(false, opened.get()) }
    }

    @Test fun emptySectionStillOpensSections() {
        compose.setContent { AirloomTheme { PlayerChannelsPanel(state.copy(channels = emptyList(), selection = ChannelSelection.Favourites), actions) } }
        compose.onNodeWithText("Список пуст").assertIsDisplayed()
        compose.waitUntil(2_000) { compose.onAllNodes(hasTestTag("player_channels_header") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player_channels_header").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitUntil(2_000) { compose.onAllNodes(hasText("Избранное") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Избранное").assertIsSelected().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitUntil(2_000) { compose.onAllNodes(hasTestTag("player_channels_header") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Список пуст").assertIsDisplayed()
    }

    @Test fun heldOkOffersFavouriteAndHideAndHidingMovesFocusFirst() {
        compose.setContent { AirloomTheme { PlayerChannelsPanel(state, actions) } }
        awaitFocus()
        val row = compose.onNodeWithContentDescription(liveRow)
        row.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithTag("row_actions_popup").assertIsDisplayed()
        // Pinning and moving belong to the main list.
        compose.onNodeWithContentDescription("Закрепить").assertDoesNotExist()
        compose.onNodeWithContentDescription("Перетянуть").assertDoesNotExist()
        compose.onNodeWithContentDescription("В избранное").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("row_actions_popup").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("setFavourite:live:true"), calls) }
        awaitFocused(liveRow)
        row.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithContentDescription("Скрыть").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithContentDescription("Скрыть").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("row_actions_popup").assertDoesNotExist()
        awaitFocused(newsRow)
        compose.runOnIdle { assertEquals("setHidden:live:true", calls.last()) }
    }

    @Test fun droppingAFavouriteInFavouritesMovesFocusFirst() {
        compose.setContent { AirloomTheme { PlayerChannelsPanel(state.copy(selection = ChannelSelection.Favourites,
            channels = listOf(news, live.copy(favourite = true))), actions) } }
        awaitFocus()
        compose.onNodeWithContentDescription(newsRow).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithContentDescription(newsRow).performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithContentDescription("Убрать из избранного").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("row_actions_popup").assertDoesNotExist()
        awaitFocused(liveRow)
        compose.runOnIdle { assertEquals(listOf("setFavourite:news:false"), calls) }
    }

    @Test fun programmeUpdatesKeepUserFocusAndSaveSyntheticDrawer() {
        val current = mutableStateOf(state)
        compose.setContent { AirloomTheme { PlayerChannelsPanel(current.value, actions) } }
        awaitFocus()
        val other = compose.onNodeWithContentDescription(newsRow)
        other.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnIdle { current.value = state.copy(now = now + 1_000) }
        other.assertIsFocused()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, "player-channel-drawer.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun largeCatalogueInitiallyFocusesPlayingChannelWithoutComposingAllRows() {
        val catalogue = (0 until 2_000).map { index -> live.copy(id = "channel-$index", name = "Канал $index", order = index) }
        val playing = catalogue[1_900]
        compose.setContent { AirloomTheme { PlayerChannelsPanel(state.copy(allChannels = catalogue, channels = catalogue,
            programmes = emptyMap(), player = state.player.copy(channel = playing, request = PlaybackRequest.Live(playing))), actions) } }
        awaitFocused("Канал 1900: Нет программы, в эфире")
        compose.onNodeWithContentDescription("Канал 1900: Нет программы, в эфире").assertIsFocused()
        // The card opens with the playing channel a couple of rows from the top, not on the first rows of the list.
        compose.onNodeWithContentDescription("Канал 1898: Нет программы").assertExists()
        compose.onNodeWithContentDescription("Канал 0: Нет программы").assertDoesNotExist()
        org.junit.Assert.assertTrue(compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size < 20)
    }

    @Test fun channelsButtonOpensSwitcher() {
        compose.setContent { AirloomPlayerOverlay(state.copy(playerControls = true), actions) }
        compose.onNodeWithText("Каналы").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(true, opened.get()) }
    }

    @Test fun oneBackOnFocusedChannelClosesSwitcher() {
        compose.setContent { AirloomTheme { PlayerChannelsPanel(state.copy(quickChannels = true), actions) } }
        compose.waitUntil(2_000) { compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Мир ТВ: В сердце природы, в эфире").assertIsFocused()
            .performKeyInput { pressKey(Key.Back) }
        compose.runOnIdle { assertEquals(false, opened.get()) }
    }

    @Test fun liveControlsOpenSwitcherWithUpAndDown() {
        compose.setContent { AirloomPlayerOverlay(state.copy(playerControls = true), actions) }
        compose.waitUntil(2_000) { compose.onAllNodes(hasText("Пауза") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        // Live has no timeline to step to: both arrows lead to the channels.
        compose.onNodeWithText("Пауза").performKeyInput { pressKey(Key.DirectionUp) }
        compose.runOnIdle { assertEquals(true, opened.getAndSet(null)) }
        compose.onNodeWithText("Пауза").performKeyInput { pressKey(Key.DirectionDown) }
        compose.runOnIdle { assertEquals(true, opened.get()) }
    }

    @Test fun archiveControlsKeepUpForTheTimelineAndOpenSwitcherPastIt() {
        val programme = Programme("p1", "epg", live.id, "В сердце природы", now - 3_600_000, now - 1_800_000)
        compose.setContent { AirloomPlayerOverlay(state.copy(playerControls = true,
            player = state.player.copy(request = PlaybackRequest.Archive(live, programme), isSeekable = true)), actions) }
        compose.waitUntil(2_000) { compose.onAllNodes(hasText("Пауза") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Пауза").performKeyInput { pressKey(Key.DirectionUp) }
        val timeline = compose.onNodeWithContentDescription("Позиция воспроизведения")
        timeline.assertIsFocused()
        compose.runOnIdle { assertNull(opened.get()) }
        // ↓ from the timeline returns to the buttons; ↑ has nothing above it and ↓ from the buttons nothing below.
        timeline.performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Пауза").assertIsFocused()
        compose.runOnIdle { assertNull(opened.get()) }
        compose.onNodeWithText("Пауза").performKeyInput { pressKey(Key.DirectionUp) }
        timeline.assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.runOnIdle { assertEquals(true, opened.getAndSet(null)) }
        timeline.performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Пауза").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.runOnIdle { assertEquals(true, opened.get()) }
    }

    @Test fun errorActionsKeepDpadFocusAndOfferAnotherChannel() {
        val failed = state.copy(playerControls = true, player = state.player.copy(error = "Поток временно недоступен"))
        val current = mutableStateOf(failed)
        compose.setContent { AirloomPlayerOverlay(current.value, actions) }
        compose.waitUntil(2_000) { compose.onNodeWithText("Повторить").fetchSemanticsNode().config.contains(SemanticsActions.RequestFocus) }
        val retry = compose.onNodeWithText("Повторить")
        retry.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        // The arrows walk the card's own actions and open nothing by themselves.
        retry.assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.runOnIdle { assertNull(opened.get()) }
        val another = compose.onNodeWithText("Другой канал")
        another.assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Настройки сети").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("К каналам").assertIsFocused()
        another.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        another.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(true, opened.get()) }
        // The channel card stands in for the error, and the failed channel's row opens it again.
        compose.runOnIdle { current.value = failed.copy(playerControls = false, quickChannels = true) }
        awaitFocused(liveRow)
        compose.onNodeWithText("Повторить").assertDoesNotExist()
        compose.onNodeWithContentDescription(liveRow).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("live", played.get()) }
        // Closed without a choice, the card returns with its focus.
        compose.runOnIdle { current.value = failed.copy(playerControls = false) }
        compose.waitUntil(2_000) { compose.onAllNodes(hasText("Повторить") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
    }
}
