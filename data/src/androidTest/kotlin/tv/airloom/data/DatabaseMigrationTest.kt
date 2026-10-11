package tv.airloom.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @Test fun versionFiveMigrationKeepsPreferencesAndPersistsIcon() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        try {
            val schema = JSONObject(context.assets.open("tv.airloom.data.AirloomDatabase/5.json").bufferedReader().use { it.readText() }).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                sqlite.execSQL("INSERT INTO category_preferences VALUES ('p', 'Кино', 1, 7, 1)")
                sqlite.execSQL("INSERT INTO channel_preferences VALUES ('a', 1, 0, 3, 2)")
                sqlite.execSQL("INSERT INTO channel_list_preferences VALUES ('p', 'ALL', '', 'a', 1, 2)")
                sqlite.execSQL("INSERT INTO provider_profile VALUES (1, 'FIXTURE', 'https://iptv.online', '', '', '', '', 'FULL_GZIP')")
                sqlite.version = 5
            }
            var db = Room.databaseBuilder(context, AirloomDatabase::class.java, name).addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build()
            try {
                val pref = db.dao().categoryPreference("p", "Кино")!!
                assertNull(pref.iconOverride); assertTrue(pref.pinned); assertTrue(pref.hidden); assertEquals(7, pref.position)
                assertTrue(db.dao().preference("a")!!.favourite)
                assertEquals("FIXTURE", db.dao().currentProviderProfile()?.accessKey)
                assertEquals(2, db.dao().listPreferences("p", "ALL", "").first().single().position)
                db.dao().putCategory(pref.copy(iconOverride = "cinema"))
            } finally { db.close() }
            db = Room.databaseBuilder(context, AirloomDatabase::class.java, name).build()
            try { assertEquals("cinema", db.dao().categoryPreference("p", "Кино")!!.iconOverride) }
            finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun versionSevenMigrationKeepsProgrammesAndSwapsTheSearchIndex() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        try {
            val schema = JSONObject(context.assets.open("tv.airloom.data.AirloomDatabase/7.json").bufferedReader().use { it.readText() }).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                sqlite.execSQL("INSERT INTO programmes VALUES ('e', 'g', 'p', 'one', 'Вечерние новости', 1000, 2000, 'Описание', '', '')")
                sqlite.version = 7
            }
            val db = Room.databaseBuilder(context, AirloomDatabase::class.java, name).addMigrations(MIGRATION_7_8).build()
            try {
                val indices = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'programmes'").use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                assertTrue("index_programmes_sourceId_generation_start_id_title" in indices)
                assertFalse("index_programmes_sourceId_generation_start_id" in indices)
                val kept = db.openHelper.readableDatabase.query("SELECT title, description FROM programmes").use { it.moveToFirst(); it.getString(0) to it.getString(1) }
                assertEquals("Вечерние новости" to "Описание", kept)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun versionThreeMigrationKeepsCatalogueAndPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        try {
            val schema = JSONObject(context.assets.open("tv.airloom.data.AirloomDatabase/3.json").bufferedReader().use { it.readText() }).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                sqlite.execSQL("INSERT INTO playlists VALUES ('playlist', 'Existing', 'https://example.test/list.m3u', 'URL', 'Test/1', '', 123, NULL, 1)")
                sqlite.execSQL("INSERT INTO category_preferences VALUES ('playlist', 'Новости', 1, 7)")
                sqlite.execSQL("INSERT INTO channel_preferences VALUES ('channel', 1, 0, 3, 2)")
                sqlite.execSQL("INSERT INTO provider_profile VALUES (1, 'FIXTURE', 'https://iptv.online', '', '', '', '', 'FULL_GZIP')")
                sqlite.version = 3
            }
            var database = Room.databaseBuilder(context, AirloomDatabase::class.java, name).addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build()
            try {
                assertEquals(1, database.dao().allPlaylists().size)
                assertEquals("FIXTURE", database.dao().currentProviderProfile()?.accessKey)
                assertTrue(database.dao().preference("channel")!!.favourite)
                val category = database.dao().categoryPreference("playlist", "Новости")!!
                assertTrue(category.hidden); assertEquals(7, category.position); assertFalse(category.pinned)
                database.dao().putCategory(category.copy(pinned = true))
            } finally { database.close() }
            database = Room.databaseBuilder(context, AirloomDatabase::class.java, name).addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build()
            try { assertTrue(database.dao().categoryPreference("playlist", "Новости")!!.pinned) }
            finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun versionFourMigrationRetainsPinsAndAddsIndependentLists() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        try {
            val schema = JSONObject(context.assets.open("tv.airloom.data.AirloomDatabase/4.json").bufferedReader().use { it.readText() }).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                sqlite.execSQL("INSERT INTO category_preferences VALUES ('p', 'Кино', 0, 7, 1)")
                sqlite.execSQL("INSERT INTO channel_preferences VALUES ('a', 1, 0, 3, 2)")
                sqlite.version = 4
            }
            var db = Room.databaseBuilder(context, AirloomDatabase::class.java, name).addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build()
            try {
                assertTrue(db.dao().categoryPreference("p", "Кино")!!.pinned)
                assertTrue(db.dao().preference("a")!!.favourite)
                assertTrue(db.dao().listPreferences("p", "ALL", "").first().isEmpty())
                db.dao().putListPreference(ChannelListPreferenceEntity("p", "CATEGORY", "Кино", "a", true, 2))
            } finally { db.close() }
            db = Room.databaseBuilder(context, AirloomDatabase::class.java, name).build()
            try { assertEquals(2, db.dao().listPreferences("p", "CATEGORY", "Кино").first().single().position) }
            finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun previousCataloguesAreResetForSingleProviderProfile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (version in listOf(1, 2)) {
            val name = "migration-${UUID.randomUUID()}.db"
            val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
            try {
            val schema = JSONObject(context.assets.open("tv.airloom.data.AirloomDatabase/$version.json").bufferedReader().use { it.readText() })
                .getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                sqlite.execSQL("INSERT INTO playlists VALUES ('playlist', 'Existing', 'https://example.test/list.m3u', 'URL', 'Test/1', '', 123, NULL, 1)")
                sqlite.version = version
            }
            val database = Room.databaseBuilder(context, AirloomDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build()
            try {
                assertTrue(database.dao().allPlaylists().isEmpty())
                assertNull(database.dao().currentProviderProfile())
                database.dao().putProviderProfile(ProviderProfileEntity(accessKey = "FIXTURE", baseOrigin = "https://iptv.online", playlistOrigin = "", epgOrigin = "", logoOrigin = "", streamOrigin = "", epgVariant = "FULL_GZIP"))
                assertEquals("FIXTURE", database.dao().currentProviderProfile()?.accessKey)
            } finally { database.close() }
            } finally { context.deleteDatabase(name) }
        }
    }
}
