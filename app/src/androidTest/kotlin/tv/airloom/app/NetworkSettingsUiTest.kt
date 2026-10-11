package tv.airloom.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import tv.airloom.app.ui.NetworkSettings
import tv.airloom.app.ui.AirloomPlayerOverlay
import tv.airloom.core.*
import tv.airloom.playback.*

@OptIn(ExperimentalTestApi::class)
class NetworkSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun profilesAdvancedValuesResetAndDpadFocus() {
        val settings = mutableStateOf(AppSettings())
        val actions = Proxy.newProxyInstance(UiActions::class.java.classLoader, arrayOf(UiActions::class.java)) { proxy, method, args ->
            if (method.name == "equals") return@newProxyInstance proxy === args?.firstOrNull()
            if (method.name == "hashCode") return@newProxyInstance System.identityHashCode(proxy)
            if (method.name == "toString") return@newProxyInstance "NetworkActions"
            if (method.name == "changeSettings") {
                @Suppress("UNCHECKED_CAST") val change = args!![0] as (AppSettings) -> AppSettings
                settings.value = change(settings.value)
            }
            null
        } as UiActions
        compose.setContent { NetworkSettings(settings.value.playbackNetwork, actions) }
        compose.onNodeWithTag("network_profile").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("network_profile").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("network_profile_1").assertIsFocused()
        compose.onNodeWithTag("network_profile_1").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(2, settings.value.playbackNetwork.profile)
        compose.onNodeWithTag("network_profile").assertIsFocused()
        compose.onNodeWithTag("network_attempts").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("network_attempts_0").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(0, settings.value.playbackNetwork.reconnectAttempts)
        compose.onNodeWithTag("network_advanced").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("network_buffer").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("network_buffer_1").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(-1, settings.value.playbackNetwork.profile)
        compose.onNodeWithTag("network_reset").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(PlaybackNetworkSettings(), settings.value.playbackNetwork)
    }

    @Test fun sectionIsReachableFromFullSettingsWithDpad() {
        val actions = Proxy.newProxyInstance(UiActions::class.java.classLoader, arrayOf(UiActions::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SettingsActions"
                else -> null
            }
        } as UiActions
        compose.setContent { tv.airloom.app.ui.SettingsScreen(UiState(loading = false, screen = Screen.SETTINGS), actions) }
        compose.onNodeWithText("Сеть и буфер").assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Сеть и буфер").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("network_profile").assertIsDisplayed()
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("network_settings"))).assertExists()
    }

    @Test fun recoveryIsNonModalAndFinalErrorOffersNetworkSettings() {
        val channel = Channel("test", "test", "Тест", "https://example.invalid/test")
        val state = mutableStateOf(UiState(loading = false, screen = Screen.PLAYER, playerControls = true,
            player = PlaybackState(request = PlaybackRequest.Live(channel), channel = channel,
                playWhenReady = true, recovery = PlaybackRecovery(2, 5))))
        var action: String? = null
        val actions = Proxy.newProxyInstance(UiActions::class.java.classLoader, arrayOf(UiActions::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "NetworkActions"
                else -> { action = method.name; null }
            }
        } as UiActions
        compose.setContent { AirloomPlayerOverlay(state.value, actions) }
        compose.onNodeWithTag("player_recovery").assertIsDisplayed()
        compose.onNodeWithText("Повторить").assertDoesNotExist()
        compose.onNodeWithText("Пауза").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals("playerTogglePause", action) }
        compose.runOnIdle { state.value = state.value.copy(player = state.value.player.copy(recovery = null, error = "Нет соединения", reconnectAttempts = 5, playWhenReady = false)) }
        compose.onNodeWithText("Переподключений: 5").assertIsDisplayed()
        compose.onNodeWithText("Настройки сети").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals("playerNetworkSettings", action)
            state.value = state.value.copy(player = state.value.player.copy(restartRequired = true))
        }
        compose.onNodeWithText("Повторить").assertDoesNotExist()
        compose.onNodeWithText("Начать заново").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals("playerRestart", action) }
    }
}
