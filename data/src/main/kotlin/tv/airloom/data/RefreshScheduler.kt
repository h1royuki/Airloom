package tv.airloom.data

import android.content.Context
import androidx.work.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import tv.airloom.core.AirloomRepository
import tv.airloom.core.RefreshIntervals
import tv.airloom.core.RefreshKind
import java.util.concurrent.TimeUnit

object RefreshScheduler {
    fun schedule(context: Context, playlistHours: Int, epgHours: Int) {
        val work = WorkManager.getInstance(context)
        fun update(name: String, kind: String, hours: Int) {
            if (hours == 0) work.cancelUniqueWork(name)
            else work.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<RefreshWorker>(hours.toLong(), TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(workDataOf("kind" to kind)).addTag("airloom-$kind-${hours}h").build())
        }
        update("airloom-playlists", "playlist", playlistHours.takeIf { it in RefreshIntervals.playlistHours } ?: 24)
        update("airloom-epg", "epg", epgHours.takeIf { it in RefreshIntervals.epgHours } ?: 6)
        context.getExternalFilesDir("import")?.mkdirs()
        java.io.File(context.filesDir, "import").mkdirs()
    }
}
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RefreshEntryPoint { fun repository(): AirloomRepository }
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(applicationContext, RefreshEntryPoint::class.java).repository()
        val kind = if (inputData.getString("kind") == "playlist") RefreshKind.PLAYLIST else RefreshKind.EPG
        val success = repository.refreshScheduled(kind)
        return if (!success && runAttemptCount < 3) Result.retry() else if (!success) Result.failure() else Result.success()
    }
}
