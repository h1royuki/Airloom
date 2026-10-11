package tv.airloom.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import tv.airloom.app.ui.AirloomApp
import tv.airloom.app.ui.AirloomPlayerOverlay
import tv.airloom.core.*
import java.io.IOException

/** TV-only wizard tests use an isolated repository and never contact the provider. */
@OptIn(ExperimentalTestApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class OnboardingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var repository: WizardTestRepository
    private var store = ViewModelStore()
    private lateinit var model: AirloomViewModel
    private var activeModel by mutableStateOf<AirloomViewModel?>(null)

    @Before fun setup() {
        repository = WizardTestRepository()
        compose.runOnUiThread { createModel() }
        compose.setContent {
            activeModel?.let { current ->
                val state by current.state.collectAsState()
                Box { AirloomApp(state, current) { }; AirloomPlayerOverlay(state, current) }
            }
        }
        compose.waitUntil(10_000) { !model.state.value.loading }
    }

    private fun createModel() {
        model = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AirloomViewModel(repository, compose.activity.applicationContext) as T
        })[AirloomViewModel::class.java]
        activeModel = model
    }

    @After fun cleanup() { compose.runOnUiThread { activeModel = null; store.clear() } }

    @Test fun recentChannelsRecordOpeningAndSurviveRefreshAndRecreation() {
        val a = Channel("recent-a", "test", "Канал А", "fixture://a")
        val b = Channel("recent-b", "test", "Канал Б", "fixture://b")
        compose.runOnIdle {
            repository.seedCompletedGuide(a, StartupMode.GUIDE)
            repository.replaceChannels(listOf(a, b))
            activeModel = null
            store.clear()
            store = ViewModelStore()
            createModel()
        }
        compose.waitUntil(5_000) { model.state.value.allChannels.size == 2 }
        compose.runOnIdle { model.focusProgramme(b, null) }
        assertTrue(model.state.value.settings.recentChannelIds.isEmpty())
        compose.onNodeWithText("Последние").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Список пуст").assertIsDisplayed()
        compose.onNodeWithText("TV").assertDoesNotExist()
        compose.onNodeWithTag("channels_empty_details").assertIsDisplayed()
        java.io.File(compose.activity.cacheDir, "synthetic-empty-recents.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.runOnIdle { model.play(a); model.play(b); model.play(a); model.navigate(Screen.GUIDE) }
        compose.waitUntil(5_000) { repository.settings.value.recentChannelIds == listOf(a.id, b.id) }
        compose.waitUntil(5_000) { model.state.value.channels.map { it.id } == listOf(a.id, b.id) }
        compose.runOnIdle { repository.replaceChannels(listOf(a.copy(name = "Канал А обновлён"), b.copy(hidden = true))) }
        compose.waitUntil(5_000) { model.state.value.channels.map { it.id } == listOf(a.id) && model.state.value.channels.first().name == "Канал А обновлён" }
        compose.runOnIdle { repository.replaceChannels(listOf(b)) }
        compose.waitUntil(5_000) { model.state.value.channels.map { it.id } == listOf(b.id) }
        assertEquals(listOf(a.id, b.id), model.state.value.settings.recentChannelIds)
        compose.runOnIdle { activeModel = null; store.clear(); store = ViewModelStore(); createModel() }
        compose.waitUntil(5_000) { !model.state.value.loading && model.state.value.channels.map { it.id } == listOf(b.id) }
        assertEquals("Последние", model.state.value.selectedCategory)
        assertEquals(listOf(a.id, b.id), model.state.value.settings.recentChannelIds)
        assertNull(model.state.value.player.request)
    }

    private fun seedGuide(channels: List<Channel>) {
        compose.runOnIdle {
            activeModel = null; store.clear()
            repository.seedCompletedGuide(channels.first(), StartupMode.GUIDE)
            repository.replaceChannels(channels)
            repository.settings.value = repository.settings.value.copy(preview = false, logos = false)
            store = ViewModelStore(); createModel()
        }
        compose.waitUntil(5_000) { model.state.value.screen == Screen.GUIDE && model.state.value.allChannels.size == channels.size && model.state.value.channelPreferenceScope != null }
    }

    @Test fun searchLoadsCurrentEpgAndFiltersArchivesAndHiddenCategories() {
        val now = System.currentTimeMillis()
        val a = Channel("search-a", "test", "Матч", "https://example.invalid/live/index.m3u8", group = "Спорт", catchup = CatchupSpec("flussonic", days = 2))
        val b = a.copy(id = "search-b", name = "Без EPG", group = "Новости")
        seedGuide(listOf(a,b))
        val live = Programme("live", "epg", "one", "Матч сейчас", now - 60_000, now + 60_000)
        val past = live.copy(id = "past", title = "Матч вчера", start = now - 120_000, end = now - 60_000)
        val future = live.copy(id = "future", title = "Матч завтра", start = now + 60_000, end = now + 120_000)
        assertEquals(1, SearchResultBuilder.build("Матч", listOf(a,b), listOf(a to live, a to past), mapOf(a.id to listOf(live)), now).archive.size)
        compose.runOnIdle { repository.testProgrammes.value = mapOf(a.id to listOf(live,past,future)); model.navigate(Screen.SEARCH); model.search("Матч") }
        compose.waitUntil(5000) { model.state.value.searchLive.singleOrNull()?.programme?.id == "live" && model.state.value.searchArchive.size == 1 }
        assertEquals(listOf("past"), model.state.value.searchArchive.map { it.second.id })
        compose.runOnIdle { runBlocking { repository.setCategoryHidden("test", "Спорт", true) } }
        compose.waitUntil(5000) { model.state.value.searchLive.isEmpty() && model.state.value.searchArchive.isEmpty() }
        compose.runOnIdle { model.search("Без EPG") }
        compose.waitUntil(5000) { model.state.value.searchLive.singleOrNull()?.channel?.id == b.id }
        assertNull(model.state.value.searchLive.single().programme)
        assertNull(model.state.value.player.request)
    }

    @Test fun scopedPinsAndMovementSaveCancelAndRollback() {
        val a = Channel("a", "test", "А", "fixture://a", group = "Новости", number = 0)
        val b = Channel("b", "test", "Б", "fixture://b", group = "Новости", number = 1)
        val c = Channel("c", "test", "В", "fixture://c", group = "Кино", number = 2)
        seedGuide(listOf(a,b,c))
        compose.runOnIdle { model.selectCategory("Новости") }
        compose.waitUntil(5_000) { model.state.value.channelPreferenceScope?.category == "Новости" }
        compose.runOnIdle { model.setChannelPinned(b, true) }
        compose.waitUntil(5_000) { model.state.value.channelPinned("b") && model.state.value.channels.first().id == "b" }
        compose.runOnIdle { model.startMove(a); model.moveChannel(-1) }
        assertEquals(listOf("b","a"), model.state.value.movingIds)
        compose.runOnIdle { model.finishMove(false); model.setChannelPinned(b, false) }
        compose.waitUntil(5_000) { !model.state.value.channelPinned("b") && model.state.value.channels.first().id == "a" }
        compose.runOnIdle { model.startMove(b); model.moveChannel(-1); model.finishMove(false) }
        assertEquals(listOf("a","b"), model.state.value.channels.map { it.id })
        compose.runOnIdle { model.startMove(b); model.moveChannel(-1); model.finishMove(true) }
        compose.waitUntil(5_000) { !model.state.value.moveSaving && model.state.value.movingChannelId == null && model.state.value.channels.first().id == "b" }
        compose.runOnIdle { model.selectCategory("Все каналы") }
        compose.waitUntil(5_000) { model.state.value.channelPreferenceScope?.kind == ChannelListKind.ALL }
        assertEquals(listOf("a","b","c"), model.state.value.channels.map { it.id })
        compose.runOnIdle { model.selectCategory("Новости") }
        compose.waitUntil(5_000) { model.state.value.channelPreferenceScope?.category == "Новости" && model.state.value.channels.first().id == "b" }
        compose.runOnIdle { repository.failReorder = true; model.startMove(a); model.moveChannel(-1); model.finishMove(true) }
        compose.waitUntil(5_000) { !model.state.value.moveSaving && model.state.value.error != null }
        assertEquals(listOf("b","a"), model.state.value.channels.map { it.id })
        compose.runOnIdle { model.dismissMessage(); model.startMove(a); repository.replaceChannels(listOf(a,c)) }
        compose.waitUntil(5_000) { model.state.value.movingChannelId == null && model.state.value.allChannels.size == 2 }
        assertNull(model.state.value.player.request)
    }

    @Test fun categoriesMoveWithoutChangingPinsAndCancelOnRefresh() {
        val a = Channel("a", "test", "А", "fixture://a", group = "Новости", number = 0)
        val b = Channel("b", "test", "Б", "fixture://b", group = "Кино", number = 1)
        seedGuide(listOf(a,b))
        val cinema = model.state.value.categories.first { it.name == "Кино" }
        compose.runOnIdle { model.startCategoryMove(cinema); model.moveCategoryRow(-1) }
        assertEquals(listOf("Кино", "Новости"), model.state.value.movingCategoryNames)
        compose.runOnIdle { model.finishCategoryMove(false) }
        assertEquals(listOf("Новости", "Кино"), model.state.value.categories.map { it.name })
        compose.runOnIdle { model.startCategoryMove(cinema); model.moveCategoryRow(-1); model.finishCategoryMove(true) }
        compose.waitUntil(5_000) { !model.state.value.moveSaving && model.state.value.movingCategory == null && model.state.value.categories.first().name == "Кино" }
        val news = model.state.value.categories.first { it.name == "Новости" }
        compose.runOnIdle { model.setCategoryPinned(news, true) }
        compose.waitUntil(5_000) { model.state.value.categories.first { it.name == "Новости" }.pinned }
        compose.runOnIdle { model.startCategoryMove(cinema); model.moveCategoryRow(-1) }
        assertEquals(listOf("Кино", "Новости"), model.state.value.movingCategoryNames)
        compose.runOnIdle { repository.replaceChannels(listOf(a,b.copy(group = "Новое кино"))) }
        compose.waitUntil(5_000) { model.state.value.movingCategory == null }
    }

    @Test fun physicalLongOkMoveScrollCancelAndHideKeepFocus() {
        val channels = (0..13).map { Channel("drag-$it", "test", "Канал $it", "fixture://$it", group = "Новости", number = it) }
        seedGuide(channels)
        val row = compose.onNodeWithContentDescription("Канал 0: Нет программы")
        row.assertIsFocused()
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val time = android.os.SystemClock.uptimeMillis()
        automation.injectInputEvent(android.view.KeyEvent(time, time, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER, 0), true)
        automation.injectInputEvent(android.view.KeyEvent(time, time + 600, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER, 1), true)
        compose.onNodeWithTag("row_actions_popup").assertIsDisplayed()
        automation.injectInputEvent(android.view.KeyEvent(time, time + 610, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER, 0), true)
        compose.onNodeWithTag("row_actions_popup").assertIsDisplayed()
        assertNull(model.state.value.player.request)
        java.io.File(compose.activity.cacheDir, "synthetic-row-popup.png").outputStream().use { automation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithContentDescription("Перетянуть").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithContentDescription("Перетянуть").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(5_000) { model.state.value.movingChannelId == "drag-0" }
        row.assertIsFocused()
        repeat(10) { row.performKeyInput { pressKey(Key.DirectionDown) } }
        row.assertIsFocused()
        val listBounds = compose.onNodeWithTag("channels_list").getUnclippedBoundsInRoot()
        val rowBounds = row.getUnclippedBoundsInRoot()
        assertTrue(rowBounds.top >= listBounds.top && rowBounds.bottom <= listBounds.bottom)
        row.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(5_000) { !model.state.value.moveSaving && model.state.value.movingChannelId == null }
        assertEquals("drag-0", model.state.value.channels[10].id)
        assertNull(model.state.value.player.request)
        row.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithContentDescription("Перетянуть").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithContentDescription("Перетянуть").performKeyInput { pressKey(Key.DirectionCenter) }
        row.performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.Back) }
        assertNull(model.state.value.movingChannelId)
        assertEquals("drag-0", model.state.value.channels[10].id)
        row.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithContentDescription("Скрыть").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithContentDescription("Скрыть").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(5_000) { model.state.value.channels.none { it.id == "drag-0" } }
        compose.onNodeWithContentDescription("Канал 11: Нет программы").assertIsFocused()
    }

    @Test fun categoryPopupMoveWithScrollAndBackRestoresOrder() {
        val channels = (0..12).map { Channel("category-$it", "test", "Канал $it", "fixture://$it", group = "Группа $it", number = it) }
        seedGuide(channels)
        val row = compose.onNodeWithText("Группа 0")
        row.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        row.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithContentDescription("Перетянуть").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithContentDescription("Перетянуть").performKeyInput { pressKey(Key.DirectionCenter) }
        val moving = compose.onNodeWithText("↕ Группа 0")
        moving.assertIsFocused()
        repeat(9) { moving.performKeyInput { pressKey(Key.DirectionDown) } }
        moving.assertIsFocused()
        val listBounds = compose.onNodeWithTag("guide_categories_list").getUnclippedBoundsInRoot()
        val rowBounds = moving.getUnclippedBoundsInRoot()
        assertTrue(rowBounds.top >= listBounds.top && rowBounds.bottom <= listBounds.bottom)
        moving.performKeyInput { pressKey(Key.Back) }
        assertNull(model.state.value.movingCategory)
        assertEquals("Группа 0", model.state.value.categories.first().name)
        compose.onNodeWithText("Группа 0").assertIsFocused()
    }

    private fun begin() {
        compose.onNodeWithText("Начать").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithContentDescription("Ключ доступа").assertExists()
        val domain = compose.onNodeWithTag("domain_picker_base")
        compose.waitUntil(5_000) { isFocused().matches(domain.fetchSemanticsNode()) }
    }

    private fun typeKey(value: String) {
        val field = compose.onNodeWithContentDescription("Ключ доступа")
        field.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        field.assertIsFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "OK — изменить"))
        field.performKeyInput { pressKey(Key.DirectionCenter) }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Назад — закончить")).assert(hasSetTextAction())
        field.performTextReplacement(value)
        field.performImeAction()
    }

    private fun assertNoPlayback() = compose.runOnIdle {
        assertNull(model.state.value.player.request)
        assertEquals(0, (model.playback as tv.airloom.playback.AirloomPlayer).player.mediaItemCount)
        assertFalse((model.playback as tv.airloom.playback.AirloomPlayer).player.playWhenReady)
    }

    @Test fun wizardRequiresKeyAndAllowsDpadNavigation() {
        val start = compose.onNodeWithText("Начать")
        compose.waitUntil(5_000) { isFocused().matches(start.fetchSemanticsNode()) }
        start.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Проверить и загрузить каналы").assertIsNotEnabled()
        compose.onNodeWithTag("domain_picker_base").assertExists()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("domain_option_base_online").assertExists()
        compose.onNodeWithTag("domain_option_base_me").assertExists()
        compose.onNodeWithTag("domain_option_base_custom").assertExists()
        assertNoPlayback()
    }

    @Test fun dpadCanPasteKeyFromClipboardWithoutOpeningKeyboard() {
        begin()
        compose.runOnUiThread {
            compose.activity.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("test key", "FIXTUREKEY1234"))
        }
        val field = compose.onNodeWithContentDescription("Ключ доступа")
        field.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        field.performKeyInput { pressKey(Key.DirectionRight) }
        val paste = compose.onNodeWithTag("entry_paste_Ключ доступа")
        paste.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals("FIXTUREKEY1234", model.state.value.onboarding.provider.key)
            assertFalse(model.state.value.busy)
        }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "OK — изменить"))
        assertNoPlayback()
    }

    @Test fun domainDropdownSelectsPresetAndShowsCustomEntry() {
        begin()
        compose.onNodeWithTag("domain_picker_base").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("domain_option_base_me").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals("https://iptv.me", model.state.value.onboarding.provider.baseOrigin) }
        compose.onNodeWithTag("domain_picker_base").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("domain_option_base_custom").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithContentDescription("Свой домен · Основной домен").assertExists()
        compose.runOnIdle { assertEquals("https://", model.state.value.onboarding.provider.baseOrigin) }
        val custom = compose.onNodeWithContentDescription("Свой домен · Основной домен")
        custom.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        custom.assertIsFocused()
        custom.performKeyInput { pressKey(Key.DirectionCenter) }
        custom.assert(hasSetTextAction())
        custom.performTextReplacement("https://custom.example.test")
        custom.performImeAction()
        compose.runOnIdle { assertEquals("https://custom.example.test", model.state.value.onboarding.provider.baseOrigin) }
    }

    @Test fun backClosesDomainDropdownWithoutLeavingWizard() {
        begin()
        compose.onNodeWithTag("domain_picker_base").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("domain_option_base_me").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("domain_option_base_me").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, model.state.value.onboarding.step) }
    }

    @Test fun serviceDomainsOfferInheritanceAndStreamPlaylistDefault() {
        begin()
        compose.onNodeWithText("Отдельные домены").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("domain_picker_logo").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("domain_option_logo_inherit").assertExists()
        compose.onNodeWithTag("domain_option_logo_me").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals("https://iptv.me", model.state.value.onboarding.provider.logoOrigin) }
        compose.onNodeWithTag("domain_picker_stream").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("domain_option_stream_inherit").assertExists()
        compose.onNodeWithTag("domain_option_stream_online").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals("https://iptv.online", model.state.value.onboarding.provider.streamOrigin) }
        compose.onNodeWithTag("domain_picker_stream").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("domain_option_stream_inherit").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals("", model.state.value.onboarding.provider.streamOrigin) }
    }

    @Test fun failedProviderImportCanRetryAndKeepsKeyDraft() {
        repository.importOverride = { _, _ -> throw IOException("Источник не ответил.") }
        begin()
        typeKey("FIXTUREKEY1234")
        compose.onNodeWithText("Проверить и загрузить каналы").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.error != null }
        compose.onNodeWithText("Не удалось импортировать").assertExists()
        compose.onNodeWithText("Повторить").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { repository.importAttempts == 2 && !model.state.value.busy }
        compose.onNodeWithText("Исправить").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals("FIXTUREKEY1234", model.state.value.onboarding.provider.key) }
        assertNoPlayback()
    }

    @Test fun draftSurvivesViewModelRecreation() {
        begin()
        typeKey("FIXTUREKEY1234")
        compose.waitUntil(5_000) { repository.onboarding.value.provider.key == "FIXTUREKEY1234" }
        compose.runOnUiThread { activeModel = null; store.clear(); store = ViewModelStore(); createModel() }
        compose.waitUntil(10_000) { !model.state.value.loading }
        compose.runOnIdle {
            assertEquals(1, model.state.value.onboarding.step)
            assertEquals("FIXTUREKEY1234", model.state.value.onboarding.provider.key)
        }
        assertNoPlayback()
    }

    @Test fun providerImportCanSkipEpgAndOpenChannelsWithoutPlayback() {
        begin()
        typeKey("FIXTUREKEY1234")
        compose.onNodeWithText("Проверить и загрузить каналы").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.importResult?.channels == 1 }
        compose.onNodeWithText("Далее").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Пропустить").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Далее").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Открыть каналы").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { model.state.value.screen == Screen.GUIDE && model.state.value.channels.size == 1 }
        compose.runOnIdle {
            assertTrue(repository.onboarding.value.complete)
            assertEquals("https://iptv.online", repository.providerProfile.value?.baseOrigin)
            assertEquals(SourceKind.URL, repository.playlists.value.single().kind)
        }
        assertNoPlayback()
    }

    @Test fun wizardLetsUserChooseLiteEpgAndLoadsItSeparately() {
        begin()
        typeKey("FIXTUREKEY1234")
        compose.onNodeWithText("Проверить и загрузить каналы").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.importResult?.channels == 1 }
        compose.onNodeWithText("Далее").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Лёгкая .gz").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(EpgVariant.LITE_GZIP, model.state.value.onboarding.provider.epgVariant) }
        compose.onNodeWithText("Загрузить EPG").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.importResult?.programmes == 3 }
        compose.runOnIdle {
            assertEquals(EpgVariant.LITE_GZIP, repository.providerProfile.value?.epgVariant)
            assertEquals(1, repository.epgSources.value.size)
        }
        assertNoPlayback()
    }

    @Test fun settingsExposeDomainDropdownsForProfileAndEpg() {
        val channel = Channel("saved-channel", "saved-source", "Тестовый канал", "https://example.invalid/live.m3u8")
        compose.runOnUiThread {
            activeModel = null
            store.clear()
            repository.seedCompletedGuide(channel, StartupMode.GUIDE)
            store = ViewModelStore()
            createModel()
        }
        compose.waitUntil(10_000) { !model.state.value.loading && model.state.value.screen == Screen.GUIDE }
        compose.runOnUiThread { model.navigate(Screen.SETTINGS) }
        fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        // Domains are edited in a draft form: it opens from the profile row and closes after a successful save.
        compose.onNodeWithTag("domain_picker_settings_base").assertDoesNotExist()
        click("provider_edit")
        click("domain_picker_settings_base")
        click("domain_option_settings_base_me")
        click("provider_save")
        compose.waitUntil(5_000) { repository.providerProfile.value?.baseOrigin == "https://iptv.me" }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("provider_save").fetchSemanticsNodes().isEmpty() }
        click("settings_section_EPG")
        click("provider_edit")
        click("domain_picker_settings_epg")
        click("domain_option_settings_epg_custom")
        compose.onNodeWithContentDescription("Свой домен · Домен EPG").assertExists()
    }

    @Test fun exitStillRequiresTwoBackPresses() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals("Нажмите «Назад» ещё раз, чтобы выйти", model.state.value.message) }
        runBlocking { assertNull(withTimeoutOrNull(150) { model.exits.first() }) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        runBlocking { withTimeout(1_000) { model.exits.first() } }
    }
    @Test fun failedSettingDoesNotDiscardNewerIndependentChange() {
        compose.runOnIdle {
            repository.failNextSettings = true
            model.changeSettings { it.copy(accent = Accent.TEAL) }
            model.changeSettings { it.copy(textSize = 2) }
        }
        compose.waitUntil(5_000) { !model.state.value.settingsSaving }
        compose.runOnIdle {
            assertEquals(Accent.LAVENDER, model.state.value.settings.accent)
            assertEquals(2, model.state.value.settings.textSize)
            assertEquals(2, repository.settings.value.textSize)
            assertEquals("Не удалось сохранить изменения. Повторите попытку.", model.state.value.error)
        }
    }

    @Test fun failedWizardWriteDoesNotDiscardNewerStep() {
        compose.runOnIdle {
            repository.failNextOnboarding = true
            model.updateOnboarding(model.state.value.onboarding.copy(step = 1))
            model.updateOnboarding(model.state.value.onboarding.copy(step = 2))
        }
        compose.waitUntil(5_000) { !model.state.value.onboardingSaving }
        compose.runOnIdle {
            assertEquals(2, model.state.value.onboarding.step)
            assertEquals(2, repository.onboarding.value.step)
            assertFalse(model.state.value.error.orEmpty().contains("synthetic-private-value"))
        }
    }

    @Test fun leavingSearchCancelsPendingRepositoryWork() {
        compose.runOnIdle {
            repository.searchGate = kotlinx.coroutines.CompletableDeferred()
            repository.seedCompletedGuide(Channel("search", "p", "Матч", "https://example.invalid/live"), StartupMode.GUIDE)
            model.navigate(Screen.SEARCH)
            model.search("Матч")
        }
        compose.waitUntil(5_000) { repository.searchCalls > 0 }
        compose.runOnIdle { model.navigate(Screen.GUIDE) }
        compose.waitUntil(5_000) { repository.searchCancellations > 0 }
        compose.runOnIdle { assertEquals(LoadPhase.READY, model.state.value.searchLoad.phase) }
    }

}
