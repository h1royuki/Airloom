package tv.airloom.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.airloom.data.PreferencesStore
import tv.airloom.data.RefreshScheduler
import tv.airloom.app.ui.LogoDiskCache

@HiltAndroidApp
class AirloomApplication: Application(), ImageLoaderFactory {
    @Inject lateinit var preferences: PreferencesStore
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            while (isActive) {
                LogoDiskCache.pruneExpired(this@AirloomApplication)
                delay(24L * 60 * 60 * 1_000)
            }
        }
        appScope.launch {
            preferences.settings.retryWhen { error, _ ->
                if (error is CancellationException) throw error
                delay(5_000)
                true
            }.map { it.playlistRefreshHours to it.epgRefreshHours }.distinctUntilChanged().collect { (playlist, epg) ->
                RefreshScheduler.schedule(this@AirloomApplication, playlist, epg)
            }
        }
    }
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .diskCache(DiskCache.Builder().directory(filesDir.resolve("channel_logos")).maxSizeBytes(64L * 1024 * 1024).build())
        .respectCacheHeaders(false)
        .build()
}
