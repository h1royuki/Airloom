package tv.airloom.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing playlists, programmes, bindings and channel preferences remain untouched. */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE epg_channels ADD COLUMN icon TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE epg_sources ADD COLUMN iconScanDone INTEGER NOT NULL DEFAULT 0")
    }
}

/** The provider-only release intentionally starts with a fresh channel catalogue. */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("bindings", "programmes", "epg_channels", "epg_sources", "channel_preferences",
            "category_preferences", "channels", "playlists").forEach { db.execSQL("DELETE FROM $it") }
        db.execSQL("CREATE TABLE IF NOT EXISTS `provider_profile` (`id` INTEGER NOT NULL, `accessKey` TEXT NOT NULL, `baseOrigin` TEXT NOT NULL, `playlistOrigin` TEXT NOT NULL, `epgOrigin` TEXT NOT NULL, `logoOrigin` TEXT NOT NULL, `streamOrigin` TEXT NOT NULL, `epgVariant` TEXT NOT NULL, PRIMARY KEY(`id`))")
    }
}

/** Adds pinning without changing the catalogue or existing preferences. */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE category_preferences ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
    }
}

internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS channel_list_preferences (playlistId TEXT NOT NULL, kind TEXT NOT NULL, category TEXT NOT NULL, channelId TEXT NOT NULL, pinned INTEGER NOT NULL DEFAULT 0, position INTEGER, PRIMARY KEY(playlistId, kind, category, channelId))")
    }
}

internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE category_preferences ADD COLUMN iconOverride TEXT DEFAULT NULL")
    }
}

/** Cursor search and channel binding lookup; all catalogue rows remain intact. */
internal val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_programmes_sourceId_generation_start_id ON programmes(sourceId, generation, start DESC, id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_bindings_sourceId_epgChannelId ON bindings(sourceId, epgChannelId)")
    }
}

/** The search index gains the title, so a title filter is answered from the index alone; all rows remain intact. */
internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS index_programmes_sourceId_generation_start_id")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_programmes_sourceId_generation_start_id_title ON programmes(sourceId, generation, start DESC, id, title)")
    }
}
