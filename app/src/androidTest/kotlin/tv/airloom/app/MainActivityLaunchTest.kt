package tv.airloom.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Read-only smoke tests against the real Hilt/Room/DataStore application. */
@androidx.annotation.OptIn(UnstableApi::class)
class MainActivityLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: AirloomViewModel

    private fun ready() {
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[AirloomViewModel::class.java] }
        compose.waitUntil(15_000) { !model.state.value.loading }
    }
    private fun assertNoPlayback() = compose.runOnIdle {
        assertTrue("No playback request should exist", model.state.value.player.request == null)
        assertTrue("No playing channel should exist", model.state.value.player.channel == null)
        assertEquals(0, (model.playback as tv.airloom.playback.AirloomPlayer).player.mediaItemCount)
        assertFalse((model.playback as tv.airloom.playback.AirloomPlayer).player.playWhenReady)
    }

    @Test fun coldLaunchDoesNotPrepareVideoForAnyStartupDestination() {
        ready(); assertNoPlayback()
    }

    @Test fun cleanInstallShowsWelcomeWithoutStartingVideo() {
        ready()
        assumeTrue("Wizard already configured; user data is left intact", !model.state.value.onboarding.complete && model.state.value.onboarding.step == 0)
        compose.onNodeWithText("Начать").assertIsDisplayed()
        assertNoPlayback()
    }

    @Test fun activityRecreationPreservesSavedWizardAndRemainsIdle() {
        ready()
        val saved = model.state.value.onboarding
        compose.activityRule.scenario.recreate()
        ready()
        compose.runOnIdle { assertTrue("Saved wizard state must survive recreation", saved == model.state.value.onboarding) }
        assertNoPlayback()
    }
}
