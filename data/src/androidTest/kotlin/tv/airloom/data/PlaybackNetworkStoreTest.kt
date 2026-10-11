package tv.airloom.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import tv.airloom.core.*
import java.io.File
import java.util.UUID

class PlaybackNetworkStoreTest {
    @Test fun oldInstallInvalidValuesAndRecreationKeepOtherSettings() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "network-${UUID.randomUUID()}.preferences_pb")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var store = PreferenceDataStoreFactory.create(scope = scope) { file }
            store.edit { it[stringPreferencesKey("accent")] = "PEACH" }
            var preferences = PreferencesStore(store)
            assertEquals(PlaybackNetworkSettings(), preferences.settings.first().playbackNetwork)
            store.edit { it[intPreferencesKey("playback.bufferMs")] = -100; it[intPreferencesKey("playback.reconnectAttempts")] = 999 }
            assertEquals(PlaybackNetworkSettings(), preferences.settings.first().playbackNetwork)
            val network = PlaybackNetworkSettings(8, 5000, 30000, 60000, 90000, 90000, 5000, 10000)
            preferences.changeSettings { it.copy(playbackNetwork = network) }
            val before = preferences.settings.first()
            preferences.changeSettings { it.copy(playbackNetwork = it.playbackNetwork.copy(bufferMs = 30000)) }
            preferences.changeSettings(before.copy(playbackNetwork = network.copy(reconnectAttempts = 3)).patch(before))
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            store = PreferenceDataStoreFactory.create(scope = scope) { file }
            preferences = PreferencesStore(store)
            val restored = preferences.settings.first()
            assertEquals(network.copy(bufferMs = 30000, reconnectAttempts = 3), restored.playbackNetwork)
            assertEquals(Accent.PEACH, restored.accent)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }
}
