package com.lastwave.app.data.jellyfin.download

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

const val JF_STATE_QUEUED = 0
const val JF_STATE_DONE = 1
const val JF_STATE_FAILED = 2

/** One downloaded (or queued) Jellyfin song. A file exists once, however many albums/playlists use it. */
@Entity(tableName = "jf_tracks")
data class JfTrackEntity(
    @PrimaryKey val itemId: String,
    val title: String,
    val artist: String,
    val albumArtist: String?,
    val album: String?,
    val albumId: String?,
    val trackNumber: Int?,
    val durationMs: Long?,
    /** File extension, e.g. "flac". */
    val container: String?,
    val filePath: String?,
    val sizeBytes: Long,
    val state: Int,
    val error: String? = null,
    /** 0 = the original file; otherwise this song is fetched as an MP3 transcode at this bitrate. */
    val transcodeKbps: Int = 0,
)

/** A downloaded album or playlist. [type] is "album" or "playlist". */
@Entity(tableName = "jf_collections")
data class JfCollectionEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    val subtitle: String?,
    val autoSync: Boolean,
    val createdAt: Long,
)

/** Which songs a collection holds, in order. A song can be in many collections. */
@Entity(
    tableName = "jf_collection_tracks",
    primaryKeys = ["collectionId", "position"],
    indices = [Index("itemId")],
)
data class JfCollectionTrackEntity(
    val collectionId: String,
    val position: Int,
    val itemId: String,
)

data class CollectionProgress(val collectionId: String, val total: Int, val done: Int, val failed: Int)

@Dao
abstract class JellyDownloadDao {
    @Query("SELECT * FROM jf_collections ORDER BY createdAt DESC")
    abstract fun collections(): Flow<List<JfCollectionEntity>>

    @Query("SELECT * FROM jf_collections WHERE id = :id")
    abstract suspend fun collection(id: String): JfCollectionEntity?

    @Query("SELECT * FROM jf_tracks")
    abstract fun allTracks(): Flow<List<JfTrackEntity>>

    @Query("SELECT * FROM jf_tracks WHERE itemId = :id")
    abstract suspend fun track(id: String): JfTrackEntity?

    @Query(
        "SELECT t.* FROM jf_tracks t JOIN jf_collection_tracks c ON c.itemId = t.itemId " +
            "WHERE c.collectionId = :id ORDER BY c.position",
    )
    abstract suspend fun tracksOf(id: String): List<JfTrackEntity>

    @Query("SELECT itemId FROM jf_collection_tracks WHERE collectionId = :id")
    abstract fun trackIds(id: String): Flow<List<String>>

    @Query("SELECT * FROM jf_tracks WHERE state = 0 ORDER BY itemId")
    abstract suspend fun pending(): List<JfTrackEntity>

    @Query(
        "SELECT c.collectionId AS collectionId, COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN t.state = 1 THEN 1 ELSE 0 END), 0) AS done, " +
            "COALESCE(SUM(CASE WHEN t.state = 2 THEN 1 ELSE 0 END), 0) AS failed " +
            "FROM jf_collection_tracks c JOIN jf_tracks t ON t.itemId = c.itemId GROUP BY c.collectionId",
    )
    abstract fun progress(): Flow<List<CollectionProgress>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTrackIfAbsent(track: JfTrackEntity): Long

    @Upsert
    abstract suspend fun upsertCollection(collection: JfCollectionEntity)

    @Insert
    abstract suspend fun insertLinks(links: List<JfCollectionTrackEntity>)

    @Query("DELETE FROM jf_collection_tracks WHERE collectionId = :id")
    abstract suspend fun clearLinks(id: String)

    @Query("DELETE FROM jf_collections WHERE id = :id")
    abstract suspend fun deleteCollection(id: String)

    @Query("SELECT * FROM jf_tracks WHERE itemId NOT IN (SELECT itemId FROM jf_collection_tracks)")
    abstract suspend fun orphans(): List<JfTrackEntity>

    @Query("DELETE FROM jf_tracks WHERE itemId = :id")
    abstract suspend fun deleteTrack(id: String)

    @Query("UPDATE jf_tracks SET filePath = :path, sizeBytes = :size, state = 1, error = NULL WHERE itemId = :id")
    abstract suspend fun markDone(id: String, path: String, size: Long)

    @Query("UPDATE jf_tracks SET state = 2, error = :error WHERE itemId = :id")
    abstract suspend fun markFailed(id: String, error: String?)

    @Query("UPDATE jf_tracks SET state = 0, error = NULL WHERE state = 2")
    abstract suspend fun requeueFailed()

    @Query("UPDATE jf_collections SET autoSync = :on WHERE id = :id")
    abstract suspend fun setAutoSync(id: String, on: Boolean)

    @Transaction
    open suspend fun replaceCollection(collection: JfCollectionEntity, links: List<JfCollectionTrackEntity>) {
        upsertCollection(collection)
        clearLinks(collection.id)
        insertLinks(links)
    }
}

val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `jf_tracks` (`itemId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`artist` TEXT NOT NULL, `albumArtist` TEXT, `album` TEXT, `albumId` TEXT, `trackNumber` INTEGER, " +
                "`durationMs` INTEGER, `container` TEXT, `filePath` TEXT, `sizeBytes` INTEGER NOT NULL, " +
                "`state` INTEGER NOT NULL, `error` TEXT, PRIMARY KEY(`itemId`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `jf_collections` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, `subtitle` TEXT, `autoSync` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `jf_collection_tracks` (`collectionId` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, `itemId` TEXT NOT NULL, PRIMARY KEY(`collectionId`, `position`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_jf_collection_tracks_itemId` ON `jf_collection_tracks` (`itemId`)")
    }
}

val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `jf_tracks` ADD COLUMN `transcodeKbps` INTEGER NOT NULL DEFAULT 0")
    }
}
