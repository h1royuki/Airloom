package tv.airloom.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "provider_profile")
data class ProviderProfileEntity(@PrimaryKey val id: Int = 1, val accessKey: String, val baseOrigin: String,
    val playlistOrigin: String, val epgOrigin: String, val logoOrigin: String, val streamOrigin: String,
    val epgVariant: String)

@Entity(tableName = "playlists")
data class PlaylistEntity(@PrimaryKey val id: String, val name: String, val location: String, val kind: String, val userAgent: String, val referer: String, val lastSuccess: Long, val lastError: String?, val channelCount: Int)
@Entity(tableName = "channels", indices = [Index("playlistId"), Index("active")])
data class ChannelEntity(@PrimaryKey val id: String, val playlistId: String, val name: String, val url: String, val category: String, val tvgId: String, val tvgName: String, val logo: String, val number: Int, val catchupMode: String, val catchupSource: String, val catchupDays: Int, val correctionSeconds: Long, val userAgent: String, val referer: String, val active: Boolean = true)
@Entity(tableName = "channel_preferences")
data class PreferenceEntity(@PrimaryKey val channelId: String, val favourite: Boolean = false, val hidden: Boolean = false, val position: Int = 0, val favouritePosition: Int = 0)
@Entity(tableName = "category_preferences", primaryKeys = ["playlistId", "name"])
data class CategoryPreferenceEntity(val playlistId: String, val name: String, val hidden: Boolean = false, val position: Int = 0, @ColumnInfo(defaultValue = "0") val pinned: Boolean = false, @ColumnInfo(defaultValue = "NULL") val iconOverride: String? = null)
@Entity(tableName = "channel_list_preferences", primaryKeys = ["playlistId", "kind", "category", "channelId"])
data class ChannelListPreferenceEntity(val playlistId: String, val kind: String, val category: String, val channelId: String,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false, val position: Int? = null)
@Entity(tableName = "epg_sources", indices = [Index("playlistId")])
data class EpgSourceEntity(@PrimaryKey val id: String, val playlistId: String, val location: String, val kind: String, val offsetMinutes: Int, val lastSuccess: Long, val lastError: String?, val programmeCount: Int, val generation: String, @ColumnInfo(defaultValue = "0") val iconScanDone: Boolean = true)
@Entity(tableName = "epg_channels", primaryKeys = ["sourceId", "generation", "id"], indices = [Index("name")])
data class EpgChannelEntity(val sourceId: String, val generation: String, val id: String, val name: String, @ColumnInfo(defaultValue = "''") val icon: String = "")
@Entity(tableName = "programmes", primaryKeys = ["sourceId", "generation", "id"], indices = [Index(value = ["sourceId", "generation", "channelId", "start", "end"]), Index(value = ["sourceId", "generation", "start", "id", "title"], orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.DESC, Index.Order.ASC, Index.Order.ASC])])
data class ProgrammeEntity(val sourceId: String, val generation: String, val id: String, val channelId: String, val title: String, val start: Long, val end: Long, val description: String, val category: String, val catchupId: String)
@Entity(tableName = "bindings", indices = [Index("sourceId"), Index(value = ["sourceId", "epgChannelId"])])
data class BindingEntity(@PrimaryKey val channelId: String, val sourceId: String, val epgChannelId: String, val manual: Boolean)

@Dao
interface AirloomDao {
    @Query("SELECT * FROM provider_profile WHERE id = 1") fun providerProfile(): Flow<ProviderProfileEntity?>
    @Query("SELECT * FROM provider_profile WHERE id = 1") suspend fun currentProviderProfile(): ProviderProfileEntity?
    @Upsert suspend fun putProviderProfile(value: ProviderProfileEntity)
    @Query("SELECT * FROM playlists ORDER BY name") fun playlists(): Flow<List<PlaylistEntity>>
    @Query("SELECT * FROM playlists") suspend fun allPlaylists(): List<PlaylistEntity>
    @Query("SELECT * FROM playlists WHERE id = :id") suspend fun playlist(id: String): PlaylistEntity?
    @Upsert suspend fun putPlaylist(value: PlaylistEntity)
    @Query("UPDATE playlists SET lastError = :message WHERE id = :id") suspend fun playlistError(id: String, message: String)
    @Query("SELECT * FROM channels WHERE active = 1 AND (:playlistId IS NULL OR playlistId = :playlistId) ORDER BY number") fun channels(playlistId: String?): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE playlistId = :playlistId") suspend fun allChannels(playlistId: String): List<ChannelEntity>
    @Query("SELECT * FROM channels WHERE active = 1") suspend fun activeChannels(): List<ChannelEntity>
    @Upsert suspend fun putChannels(values: List<ChannelEntity>)
    @Query("UPDATE channels SET active = 0 WHERE playlistId = :id") suspend fun deactivate(id: String)
    @Query("SELECT * FROM channel_preferences") fun preferences(): Flow<List<PreferenceEntity>>
    @Query("SELECT * FROM channel_preferences WHERE channelId = :id") suspend fun preference(id: String): PreferenceEntity?
    @Query("SELECT * FROM channel_preferences") suspend fun allPreferences(): List<PreferenceEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun initializePreferences(values: List<PreferenceEntity>)
    @Upsert suspend fun putPreference(value: PreferenceEntity)
    @Query("SELECT * FROM category_preferences") fun categoryPreferences(): Flow<List<CategoryPreferenceEntity>>
    @Query("SELECT * FROM category_preferences WHERE playlistId = :playlistId AND name = :name") suspend fun categoryPreference(playlistId: String, name: String): CategoryPreferenceEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun initializeCategories(values: List<CategoryPreferenceEntity>)
    @Upsert suspend fun putCategory(value: CategoryPreferenceEntity)
    @Query("SELECT * FROM channel_list_preferences WHERE playlistId = :playlistId AND kind = :kind AND category = :category")
    fun listPreferences(playlistId: String, kind: String, category: String): Flow<List<ChannelListPreferenceEntity>>
    @Query("SELECT * FROM channel_list_preferences WHERE playlistId = :playlistId AND kind = :kind AND category = :category AND channelId = :channelId")
    suspend fun listPreference(playlistId: String, kind: String, category: String, channelId: String): ChannelListPreferenceEntity?
    @Query("SELECT * FROM channel_list_preferences WHERE playlistId = :playlistId AND kind = :kind AND category = :category")
    suspend fun currentListPreferences(playlistId: String, kind: String, category: String): List<ChannelListPreferenceEntity>
    @Upsert suspend fun putListPreference(value: ChannelListPreferenceEntity)
    @Upsert suspend fun putListPreferences(values: List<ChannelListPreferenceEntity>)
    @Query("DELETE FROM channel_list_preferences WHERE playlistId = :id") suspend fun deleteListPreferences(id: String)
    @Query("SELECT * FROM epg_sources") fun epgSources(): Flow<List<EpgSourceEntity>>
    @Query("SELECT * FROM epg_sources") suspend fun allEpgSources(): List<EpgSourceEntity>
    @Query("SELECT * FROM epg_sources WHERE iconScanDone = 0") suspend fun epgSourcesNeedingIconScan(): List<EpgSourceEntity>
    @Query("SELECT * FROM epg_sources WHERE id = :id") suspend fun epgSource(id: String): EpgSourceEntity?
    @Upsert suspend fun putEpgSource(value: EpgSourceEntity)
    @Query("UPDATE epg_sources SET lastError = :message WHERE id = :id") suspend fun epgError(id: String, message: String)
    @Query("UPDATE epg_sources SET iconScanDone = 1 WHERE id = :id AND generation = :generation") suspend fun markEpgIconsScanned(id: String, generation: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putProgrammes(values: List<ProgrammeEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putEpgChannels(values: List<EpgChannelEntity>)
    @Query("UPDATE epg_channels SET icon = :icon WHERE sourceId = :sourceId AND generation = :generation AND id = :id") suspend fun setEpgChannelIcon(sourceId: String, generation: String, id: String, icon: String)
    @Query("SELECT e.* FROM epg_channels e JOIN epg_sources s ON s.id = e.sourceId AND s.generation = e.generation") fun currentEpgChannels(): Flow<List<EpgChannelEntity>>
    @Query("SELECT e.* FROM epg_channels e JOIN epg_sources s ON s.id = e.sourceId AND s.generation = e.generation WHERE s.playlistId = :playlistId") suspend fun epgChannels(playlistId: String): List<EpgChannelEntity>
    @Query("SELECT e.* FROM epg_channels e JOIN epg_sources s ON s.id = e.sourceId AND s.generation = e.generation WHERE e.name GLOB :pattern OR e.id GLOB :pattern LIMIT 100") suspend fun findEpg(pattern: String): List<EpgChannelEntity>
    @Query("SELECT * FROM bindings") fun bindings(): Flow<List<BindingEntity>>
    @Query("SELECT * FROM bindings") suspend fun allBindings(): List<BindingEntity>
    @Upsert suspend fun putBindings(values: List<BindingEntity>)
    @Query("DELETE FROM bindings WHERE channelId = :id") suspend fun deleteBinding(id: String)
    @Query("SELECT p.* FROM programmes p JOIN epg_sources s ON p.sourceId = s.id AND p.generation = s.generation JOIN bindings b ON b.sourceId = p.sourceId AND b.epgChannelId = p.channelId WHERE b.channelId IN (:ids) AND p.start < :end AND p.end > :start") fun programmes(ids: List<String>, start: Long, end: Long): Flow<List<ProgrammeEntity>>
    // The title filter is evaluated inside the (sourceId, generation, start, id, title) index; only matching rows reach the table and the binding check.
    @Query("SELECT p.* FROM programmes p WHERE p.sourceId = :sourceId AND p.generation = :generation AND p.start <= MIN(:now, :cursorStart) AND p.start >= :earliest AND p.title GLOB :pattern AND p.end > p.start AND (p.start < :cursorStart OR (p.start = :cursorStart AND p.id > :cursorId)) AND EXISTS (SELECT 1 FROM bindings b JOIN channels c ON c.id = b.channelId LEFT JOIN channel_preferences cp ON cp.channelId = c.id LEFT JOIN category_preferences cat ON cat.playlistId = c.playlistId AND cat.name = c.category WHERE b.sourceId = p.sourceId AND b.epgChannelId = p.channelId AND c.active = 1 AND COALESCE(cp.hidden, 0) = 0 AND COALESCE(cat.hidden, 0) = 0 AND (:playlistId IS NULL OR c.playlistId = :playlistId)) ORDER BY p.start DESC, p.id LIMIT :limit")
    suspend fun searchPage(pattern: String, playlistId: String?, now: Long, earliest: Long,
                           sourceId: String, generation: String, cursorStart: Long, cursorId: String, limit: Int): List<ProgrammeEntity>
    @Query("UPDATE programmes SET start = start + :delta, end = end + :delta WHERE sourceId = :sourceId AND generation = :generation") suspend fun shiftProgrammes(sourceId: String, generation: String, delta: Long)
    @Query("DELETE FROM programmes WHERE sourceId = :sourceId AND generation != :keep") suspend fun pruneProgrammes(sourceId: String, keep: String)
    @Query("DELETE FROM epg_channels WHERE sourceId = :sourceId AND generation != :keep") suspend fun pruneEpgChannels(sourceId: String, keep: String)
    @Query("DELETE FROM programmes WHERE NOT EXISTS (SELECT 1 FROM epg_sources s WHERE s.id = programmes.sourceId AND s.generation = programmes.generation)") suspend fun discardAbandonedProgrammes()
    @Query("DELETE FROM epg_channels WHERE NOT EXISTS (SELECT 1 FROM epg_sources s WHERE s.id = epg_channels.sourceId AND s.generation = epg_channels.generation)") suspend fun discardAbandonedEpgChannels()
    @Query("DELETE FROM programmes WHERE sourceId = :sourceId AND generation = :generation") suspend fun discardProgrammes(sourceId: String, generation: String)
    @Query("DELETE FROM epg_channels WHERE sourceId = :sourceId AND generation = :generation") suspend fun discardEpgChannels(sourceId: String, generation: String)
    @Query("DELETE FROM bindings WHERE sourceId = :id") suspend fun deleteSourceBindings(id: String)
    @Query("DELETE FROM epg_sources WHERE id = :id") suspend fun deleteEpg(id: String)
    @Query("DELETE FROM channel_preferences WHERE channelId IN (SELECT id FROM channels WHERE playlistId = :id)") suspend fun deletePreferences(id: String)
    @Query("DELETE FROM bindings WHERE channelId IN (SELECT id FROM channels WHERE playlistId = :id)") suspend fun deletePlaylistBindings(id: String)
    @Query("DELETE FROM channels WHERE playlistId = :id") suspend fun deleteChannels(id: String)
    @Query("DELETE FROM category_preferences WHERE playlistId = :id") suspend fun deleteCategories(id: String)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: String)
}

@Database(entities = [PlaylistEntity::class, ChannelEntity::class, PreferenceEntity::class, CategoryPreferenceEntity::class, EpgSourceEntity::class, EpgChannelEntity::class, ProgrammeEntity::class, BindingEntity::class, ProviderProfileEntity::class, ChannelListPreferenceEntity::class], version = 8, exportSchema = true)
abstract class AirloomDatabase : RoomDatabase() { abstract fun dao(): AirloomDao }
