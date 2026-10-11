package tv.airloom.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import tv.airloom.core.AppSettings
import tv.airloom.core.Accent
import java.io.File
import java.util.UUID

class RecentHistoryStoreTest {
    @Test fun orderedHistorySurvivesStoreRecreationAndOtherSettingsUpdates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "recents-${UUID.randomUUID()}.preferences_pb")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var preferences = PreferencesStore(PreferenceDataStoreFactory.create(scope = scope) { file })
            assertTrue(preferences.settings.first().recentChannelIds.isEmpty())
            val ids = (0..59).map { "synthetic-$it" }
            preferences.settings(AppSettings(recentChannelIds = ids + ids))
            val stored = preferences.settings.first()
            assertEquals(ids.take(50), stored.recentChannelIds)
            preferences.settings(stored.copy(accent = Accent.PEACH))
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            preferences = PreferencesStore(PreferenceDataStoreFactory.create(scope = scope) { file })
            val restored = preferences.settings.first()
            assertEquals(ids.take(50), restored.recentChannelIds)
            assertEquals(Accent.PEACH, restored.accent)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }

    @Test fun malformedHistoryDoesNotDiscardOtherSettings() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "recents-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            store.edit { it[stringPreferencesKey("recentChannels")] = "broken"; it[stringPreferencesKey("accent")] = "TEAL" }
            val restored = PreferencesStore(store).settings.first()
            assertTrue(restored.recentChannelIds.isEmpty())
            assertEquals(Accent.TEAL, restored.accent)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }
}
