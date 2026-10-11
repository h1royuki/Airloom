package tv.airloom.app

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tv.airloom.playback.FramePreviewService
import tv.airloom.playback.PreviewFrames
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PreviewModule {
    @Provides @Singleton
    fun previews(@ApplicationContext context: Context): FramePreviewService = PreviewFrames.shared(context)
}
