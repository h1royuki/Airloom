package tv.airloom.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import tv.airloom.app.ui.AirloomApp
import tv.airloom.core.*
import java.io.File

/** Real ViewModel with an isolated synthetic catalogue. No provider or user data. */
@OptIn(ExperimentalTestApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SettingsManagerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val repository = WizardTestRepository()
    private val store = ViewModelStore()
    private lateinit var model: AirloomViewModel
    private val a = Channel("a", "fixture", "Киноклуб", "https://example.invalid/a", group = "Кино", favourite = true)
    private val b = a.copy(id = "b", name = "Скрытый канал", hidden = true, favourite = false, number = 2)
    private val c = a.copy(id = "c", name = "Живой концерт", group = "Музыка", hidden = true, number = 3)
    private val d = a.copy(id = "d", name = "Музыкальная волна", group = "Музыка", favourite = false, number = 4)
    @Before fun setup() {
        repository.seedCompletedGuide(a, StartupMode.GUIDE)
        repository.replaceChannels(listOf(a, b, c, d))
        repository.settings.value = repository.settings.value.copy(logos = false, preview = true)
        runBlocking { repository.setCategoryHidden("fixture", "Музыка", true) }
        compose.runOnUiThread {
            model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AirloomViewModel(repository, compose.activity.applicationContext) as T
            })[AirloomViewModel::class.java]
        }
        compose.setContent { val state by model.state.collectAsState(); AirloomApp(state, model) {} }
        compose.waitUntil(10_000) { !model.state.value.loading && model.state.value.allChannels.size == 4 }
        compose.runOnIdle { model.navigate(Screen.SETTINGS) }
        clickTag("settings_section_LISTS")
    }
    @After fun cleanup() { compose.runOnUiThread { store.clear() } }
    private fun clickTag(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag !in setOf("manager_batch", "manager_select_all", "manager_save_order")) node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.OnClick) { it() }; compose.waitForIdle() }
    private fun clickText(text: String) {
        val node = compose.onNodeWithText(text)
        if (text != "Отмена выбора") node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.OnClick) { it() }; compose.waitForIdle() }
    private fun longTag(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.waitForIdle()
    }
    private fun popup(label: String) {
        compose.onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
    }
    private fun saved() { compose.waitUntil(5_000) { !model.state.value.managerSaving }; compose.waitForIdle() }
    private fun closeCard() = clickText("Закрыть")
    private fun capture(name: String) {
        File(compose.activity.cacheDir, "settings-$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun hiddenCategoryAndHiddenChannelOpenWithoutRestoringOrPlaying() {
        capture("categories")
        clickTag("manager_row_Музыка")
        clickTag("manager_row_c")
        compose.onNodeWithText("Показать канал").assertExists()
        assertTrue(model.state.value.allChannels.first { it.id == "c" }.hidden)
        assertTrue(model.state.value.categories.first { it.name == "Музыка" }.hidden)
        assertNull(model.state.value.player.request)
        assertNull(model.playback.state.value.request)
        clickTag("manager_channel_visibility"); saved()
        assertFalse(model.state.value.allChannels.first { it.id == "c" }.hidden)
        assertTrue(model.state.value.categories.first { it.name == "Музыка" }.hidden)
        compose.onNode(hasText("Показать категорию") and hasAnyAncestor(isDialog())).assertExists()
        closeCard()
        compose.onNodeWithTag("manager_row_c").assertIsFocused()
    }
    @Test fun restoredCategoryRetainsIndividuallyHiddenChannels() {
        longTag("manager_row_Музыка")
        popup("Показать"); saved()
        clickTag("manager_row_Музыка")
        clickTag("manager_filter_HIDDEN")
        compose.onNodeWithTag("manager_row_c").assertExists()
        compose.onNodeWithTag("manager_row_d").assertDoesNotExist()
        assertTrue(model.state.value.allChannels.first { it.id == "c" }.hidden)
        assertNull(model.state.value.player.request)
    }
    @Test fun restoringLastHiddenChannelLeavesCardOpenAndReturnsToFilter() {
        clickTag("manager_row_Кино"); clickTag("manager_filter_HIDDEN"); clickTag("manager_row_b")
        clickTag("manager_channel_visibility"); saved()
        compose.onNodeWithText("Скрыть канал").assertExists()
        closeCard()
        compose.onNodeWithText("Скрытых элементов нет").assertExists()
        compose.onNodeWithTag("manager_filter_ALL").assertIsFocused()
    }
    @Test fun bulkFailureKeepsSelectionAndRetryRestoresOnlyChannelFlags() {
        clickTag("manager_all"); clickTag("manager_filter_HIDDEN"); longTag("manager_row_b"); popup("Выбрать несколько"); clickTag("manager_select_all")
        clickTag("manager_batch"); clickText("Показать выбранные")
        compose.onNodeWithText("2 из выбранных находятся в скрытых категориях.", substring = true).assertExists()
        repository.failListMutation = true
        clickTag("manager_apply_batch"); saved()
        assertTrue(model.state.value.allChannels.first { it.id == "b" }.hidden)
        compose.onNodeWithTag("manager_apply_batch").assertExists()
        repository.failListMutation = false
        clickTag("manager_apply_batch"); saved()
        assertFalse(model.state.value.allChannels.any { it.hidden })
        assertTrue(model.state.value.categories.first { it.name == "Музыка" }.hidden)
        assertNull(model.state.value.player.request)
    }
    @Test fun reorderIncludesHiddenAndDoesNotChangeGuideScope() {
        val guideSelection = model.state.value.selection
        clickTag("manager_row_Кино"); longTag("manager_row_b"); popup("Изменить порядок")
        val bRow = compose.onNodeWithTag("manager_row_b").performScrollTo()
        bRow.performSemanticsAction(SemanticsActions.RequestFocus)
        bRow.performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.DirectionCenter) }
        clickTag("manager_save_order"); saved()
        assertEquals("b", model.state.value.managerPreferences.minBy { it.position ?: Int.MAX_VALUE }.channelId)
        assertEquals(guideSelection, model.state.value.selection)
        assertNull(model.state.value.player.request)
    }
    @Test fun catalogueChangeCancelsUnsavedOrder() {
        clickTag("manager_all"); longTag("manager_row_a"); popup("Изменить порядок")
        compose.runOnIdle { repository.replaceChannels(listOf(a, b, c)) }
        compose.waitForIdle()
        compose.onNodeWithText("Список изменился. Несохранённый порядок отменён.").assertExists()
        compose.onNodeWithTag("manager_save_order").assertDoesNotExist()
    }
    @Test fun largeLightThemeAndCategoryReturnPreserveFilter() {
        compose.runOnIdle { model.changeSettings { it.copy(theme = ThemeMode.LIGHT, textSize = 2) } }
        clickTag("manager_filter_HIDDEN"); clickTag("manager_row_Музыка")
        capture("hidden-category-large-light")
        clickText("К категориям")
        compose.onNodeWithTag("manager_row_Музыка").assertIsFocused()
        compose.onNodeWithTag("manager_row_Кино").assertDoesNotExist()
    }
    @Test fun contextActionsAreOnlyOnLongPressAndStartSelectionWithCurrentRow() {
        compose.onNodeWithText("Выбрать").assertDoesNotExist()
        compose.onNodeWithText("Порядок").assertDoesNotExist()
        compose.onNodeWithTag("manager_actions_Кино").assertDoesNotExist()
        val row = compose.onNodeWithTag("manager_row_Кино").performScrollTo()
        row.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnUiThread {
            val time = android.os.SystemClock.uptimeMillis()
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(time, time, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER, 0))
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(time, time + 600, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER, 1))
        }
        compose.onNodeWithTag("row_actions_popup").assertExists()
        popup("Выбрать несколько")
        compose.onNodeWithText("Выбрано: 1", substring = true).assertExists()
        compose.onNodeWithTag("manager_row_Музыка").performScrollTo().performKeyInput { pressKey(Key.DirectionCenter) }
        clickText("Отмена выбора")
        compose.onNodeWithText("Выбрано:", substring = true).assertDoesNotExist()
    }
    @Test fun providerDraftCanBeKeptDiscardedAndSavedAfterFailure() {
        clickTag("settings_section_CONNECTION"); clickTag("provider_edit")
        clickTag("domain_picker_settings_base"); clickTag("domain_option_settings_base_me")
        clickText("Отмена")
        compose.onNodeWithText("Есть несохранённые изменения").assertExists()
        clickText("Продолжить редактирование")
        compose.onNodeWithTag("provider_save").assertExists()
        clickText("Отмена"); clickTag("provider_discard")
        assertEquals("https://iptv.online", model.state.value.provider?.baseOrigin)
        clickTag("provider_edit"); clickTag("domain_picker_settings_base"); clickTag("domain_option_settings_base_me")
        repository.failProviderSave = true
        clickTag("provider_save")
        compose.waitUntil(5_000) { model.state.value.error != null }
        assertEquals("https://iptv.online", model.state.value.provider?.baseOrigin)
        compose.runOnIdle { model.dismissMessage() }
        repository.failProviderSave = false
        clickTag("provider_save")
        compose.waitUntil(5_000) { model.state.value.provider?.baseOrigin == "https://iptv.me" }
        compose.waitForIdle()
        compose.onNodeWithTag("provider_save").assertDoesNotExist()
        compose.onNodeWithTag("provider_edit").assertIsFocused()
    }
    @Test fun backCancelsMoveThenOrderWithoutWriting() {
        clickTag("manager_all"); longTag("manager_row_b"); popup("Изменить порядок")
        compose.onNodeWithTag("manager_row_b").performKeyInput { pressKey(Key.DirectionUp) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("manager_save_order").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("manager_save_order").assertDoesNotExist()
        assertTrue(model.state.value.managerPreferences.isEmpty())
    }

    @Test fun longListSelectionToolbarStaysReachableAndSelectsOffscreenRows() {
        compose.runOnIdle { repository.replaceChannels((0 until 80).map { a.copy(id = "long-$it", name = "Канал $it", number = it) }) }
        compose.waitUntil(5_000) { model.state.value.allChannels.size == 80 }
        clickTag("manager_all")
        compose.onNodeWithTag("manager_list").performScrollToNode(hasTestTag("manager_row_long-75"))
        longTag("manager_row_long-75"); popup("Выбрать несколько")
        compose.onNodeWithTag("manager_batch").assertIsDisplayed()
        compose.onNodeWithTag("manager_row_long-75").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("manager_batch").assertIsFocused()
        clickTag("manager_select_all")
        compose.onNodeWithText("Выбрано: 80", substring = true).assertExists()
        capture("selection")
        clickTag("manager_batch"); clickText("Скрыть выбранные"); clickTag("manager_apply_batch"); saved()
        assertTrue(model.state.value.allChannels.all { it.hidden })
        assertNull(model.state.value.player.request)
    }

}
