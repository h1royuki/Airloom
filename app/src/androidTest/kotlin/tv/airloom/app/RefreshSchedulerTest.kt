package tv.airloom.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.airloom.data.RefreshScheduler

@RunWith(AndroidJUnit4::class)
class RefreshSchedulerTest {
    @Test fun changedIntervalsReplaceJobsAndOffCancelsThem() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val work = WorkManager.getInstance(context)
        fun active(name: String) = work.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
            .filter { !it.state.isFinished || it.state == WorkInfo.State.ENQUEUED }
        fun await(name: String, expectedTag: String?) {
            repeat(80) {
                val current = active(name)
                if (expectedTag == null && current.isEmpty()) return
                if (expectedTag != null && current.any { expectedTag in it.tags }) return
                Thread.sleep(50)
            }
            assertTrue("Expected $name to ${expectedTag ?: "stop"}", false)
        }
        try {
            RefreshScheduler.schedule(context, 12, 3)
            await("airloom-playlists", "airloom-playlist-12h")
            await("airloom-epg", "airloom-epg-3h")
            RefreshScheduler.schedule(context, 0, 0)
            await("airloom-playlists", null)
            await("airloom-epg", null)
        } finally {
            RefreshScheduler.schedule(context, 24, 6)
        }
    }
}
