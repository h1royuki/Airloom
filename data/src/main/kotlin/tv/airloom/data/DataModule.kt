package tv.airloom.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import tv.airloom.core.AirloomRepository
import tv.airloom.core.AirloomRuntime
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindings {
    @Binds @Singleton abstract fun repository(impl: RoomAirloomRepository): AirloomRepository
}
@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton fun runtime() = AirloomRuntime()
    @Provides @Singleton fun database(@ApplicationContext context: Context): AirloomDatabase = Room.databaseBuilder(context, AirloomDatabase::class.java, "airloom.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
        // Room's automatic mode falls back to TRUNCATE on low-RAM TVs, where every EPG batch would block catalogue reads.
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
    @Provides @Singleton fun preferences(@ApplicationContext context: Context): DataStore<Preferences> = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("airloom") }
    @Provides @Singleton fun http(): OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followRedirects(true).build()
}
