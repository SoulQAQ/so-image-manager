package cn.soul2.imageai.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import cn.soul2.imageai.data.db.entity.ImageEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ImageDao {
    @Query(
        """
        SELECT COUNT(*) FROM image
        WHERE availability = 'AVAILABLE' AND missing_candidate_since_epoch_millis IS NULL
        """,
    )
    abstract fun observeAvailableCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM image WHERE availability != 'AVAILABLE'")
    abstract fun observeUnavailableCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM image
        WHERE availability = 'AVAILABLE' AND missing_candidate_since_epoch_millis IS NULL
        ORDER BY sort_time_epoch_millis DESC, media_store_id DESC,
            volume_name DESC, local_id DESC
        """,
    )
    abstract fun pagingAll(): PagingSource<Int, ImageEntity>

    @Query(
        """
        SELECT * FROM image
        WHERE availability = 'AVAILABLE' AND missing_candidate_since_epoch_millis IS NULL
        ORDER BY sort_time_epoch_millis DESC, media_store_id DESC,
            volume_name DESC, local_id DESC
        """,
    )
    abstract fun pagingRecent(): PagingSource<Int, ImageEntity>

    @Query(
        """
        SELECT * FROM image
        WHERE local_id = :localId
          AND availability = 'AVAILABLE'
          AND missing_candidate_since_epoch_millis IS NULL
        LIMIT 1
        """,
    )
    abstract fun observeAvailableById(localId: Long): Flow<ImageEntity?>

    @Query(
        """
        SELECT * FROM image
        WHERE availability = 'AVAILABLE'
          AND missing_candidate_since_epoch_millis IS NULL
          AND (
            sort_time_epoch_millis > :sortTime
            OR (sort_time_epoch_millis = :sortTime AND media_store_id > :mediaStoreId)
            OR (sort_time_epoch_millis = :sortTime AND media_store_id = :mediaStoreId
                AND volume_name > :volumeName)
            OR (sort_time_epoch_millis = :sortTime AND media_store_id = :mediaStoreId
                AND volume_name = :volumeName AND local_id > :localId)
          )
        ORDER BY sort_time_epoch_millis ASC, media_store_id ASC,
            volume_name ASC, local_id ASC
        LIMIT 1
        """,
    )
    abstract fun observePreviousAvailable(
        sortTime: Long,
        mediaStoreId: Long,
        volumeName: String,
        localId: Long,
    ): Flow<ImageEntity?>

    @Query(
        """
        SELECT * FROM image
        WHERE availability = 'AVAILABLE'
          AND missing_candidate_since_epoch_millis IS NULL
          AND (
            sort_time_epoch_millis < :sortTime
            OR (sort_time_epoch_millis = :sortTime AND media_store_id < :mediaStoreId)
            OR (sort_time_epoch_millis = :sortTime AND media_store_id = :mediaStoreId
                AND volume_name < :volumeName)
            OR (sort_time_epoch_millis = :sortTime AND media_store_id = :mediaStoreId
                AND volume_name = :volumeName AND local_id < :localId)
          )
        ORDER BY sort_time_epoch_millis DESC, media_store_id DESC,
            volume_name DESC, local_id DESC
        LIMIT 1
        """,
    )
    abstract fun observeNextAvailable(
        sortTime: Long,
        mediaStoreId: Long,
        volumeName: String,
        localId: Long,
    ): Flow<ImageEntity?>

    @Query("SELECT * FROM image WHERE local_id = :localId LIMIT 1")
    abstract suspend fun getById(localId: Long): ImageEntity?

    @Transaction
    open suspend fun upsert(images: List<ImageEntity>) {
        val resolved = resolveImagesForUpsert(images, ::findExistingIdentities)
        if (resolved.isNotEmpty()) upsertResolved(resolved)
    }

    @Query(
        """
        SELECT volume_name, media_store_id, local_id
        FROM image
        WHERE volume_name = :volumeName AND media_store_id IN (:mediaStoreIds)
        """,
    )
    protected abstract suspend fun findExistingIdentities(
        volumeName: String,
        mediaStoreIds: List<Long>,
    ): List<ExistingImageIdentity>

    @Upsert
    protected abstract suspend fun upsertResolved(images: List<ImageEntity>)
}
