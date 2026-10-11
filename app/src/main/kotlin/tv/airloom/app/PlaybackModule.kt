package tv.airloom.app

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped
import tv.airloom.core.AirloomRuntime
import tv.airloom.playback.AirloomPlayer
import tv.airloom.playback.PlaybackController

@Module
@InstallIn(ViewModelComponent::class)
object PlaybackModule {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Provides @ViewModelScoped
    fun player(@ApplicationContext context: Context, runtime: AirloomRuntime): PlaybackController = AirloomPlayer(context, runtime)
}
