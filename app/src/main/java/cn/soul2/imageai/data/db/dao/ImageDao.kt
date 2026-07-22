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

    @Query("SELECT COUNT(*) FROM image WHERE availability != 'AVAILABLE' AND availability != 'REMOVED_FROM_SOIM'")
    abstract fun observeUnavailableCount(): Flow<Int>

    @Query("UPDATE image SET availability = 'REMOVED_FROM_SOIM' WHERE local_id IN (:localIds)")
    protected abstract suspend fun markRemovedFromSoim(localIds: List<Long>): Int

    @Query("DELETE FROM search_document WHERE rowid IN (:localIds)")
    protected abstract suspend fun deleteSearchDocuments(localIds: List<Long>): Int

    @Transaction
    open suspend fun removeFromSoim(localIds: List<Long>): Int {
        val ids = localIds.distinct().filter { it > 0L }
        if (ids.isEmpty()) return 0
        deleteSearchDocuments(ids)
        return markRemovedFromSoim(ids)
    }

    @Query("UPDATE image SET availability = 'ANALYSIS_REJECTED' WHERE local_id = :localId")
    protected abstract suspend fun markAnalysisRejected(localId: Long): Int

    @Transaction
    open suspend fun hideRejectedAnalysis(localId: Long): Int {
        deleteSearchDocuments(listOf(localId))
        return markAnalysisRejected(localId)
    }

    @Query("SELECT * FROM image WHERE local_id IN (:localIds) AND availability = 'AVAILABLE'")
    abstract suspend fun availableByIds(localIds: List<Long>): List<ImageEntity>

    @Query("SELECT local_id FROM image WHERE availability = 'AVAILABLE' AND missing_candidate_since_epoch_millis IS NULL")
    abstract suspend fun allAvailableIds(): List<Long>

    @Query(
        """
        SELECT image.local_id FROM image
        LEFT JOIN active_image_analysis AS active ON active.image_local_id = image.local_id
        WHERE image.availability = 'AVAILABLE'
          AND image.missing_candidate_since_epoch_millis IS NULL
          AND active.analysis_id IS NULL
        """,
    )
    abstract suspend fun allUnanalyzedAvailableIds(): List<Long>

    @Query(
        """
        SELECT * FROM image
        WHERE image.availability = 'AVAILABLE' AND image.missing_candidate_since_epoch_millis IS NULL
        ORDER BY sort_time_epoch_millis DESC, media_store_id DESC,
            volume_name DESC, local_id DESC
        """,
    )
    abstract fun pagingAll(): PagingSource<Int, ImageEntity>

    @Query(
        """
        SELECT * FROM image
        WHERE image.availability = 'AVAILABLE' AND image.missing_candidate_since_epoch_millis IS NULL
        ORDER BY sort_time_epoch_millis DESC, media_store_id DESC,
            volume_name DESC, local_id DESC
        """,
    )
    abstract fun pagingRecent(): PagingSource<Int, ImageEntity>

    @Query(
        """
        SELECT image.* FROM image
        INNER JOIN active_image_analysis AS active ON active.image_local_id = image.local_id
        WHERE image.availability = 'AVAILABLE' AND image.missing_candidate_since_epoch_millis IS NULL
        ORDER BY image.sort_time_epoch_millis DESC, image.media_store_id DESC,
            image.volume_name DESC, image.local_id DESC
        """,
    )
    abstract fun pagingAnalyzed(): PagingSource<Int, ImageEntity>

    @Query(
        """
        SELECT image.* FROM image
        LEFT JOIN active_image_analysis AS active ON active.image_local_id = image.local_id
        WHERE image.availability = 'AVAILABLE' AND image.missing_candidate_since_epoch_millis IS NULL
          AND active.analysis_id IS NULL
        ORDER BY image.sort_time_epoch_millis DESC, image.media_store_id DESC,
            image.volume_name DESC, image.local_id DESC
        """,
    )
    abstract fun pagingUnanalyzed(): PagingSource<Int, ImageEntity>

    @Query(
        """
        SELECT * FROM image
        WHERE image.availability = 'ANALYSIS_REJECTED'
        ORDER BY image.sort_time_epoch_millis DESC, image.media_store_id DESC,
            image.volume_name DESC, image.local_id DESC
        """,
    )
    abstract fun pagingRejected(): PagingSource<Int, ImageEntity>

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

    @Transaction
    open suspend fun upsertAndResolve(images: List<ImageEntity>): List<ImageEntity> {
        val resolved = resolveImagesForUpsert(images, ::findExistingIdentities)
        if (resolved.isEmpty()) return emptyList()
        upsertResolved(resolved)
        return resolveImagesForUpsert(resolved, ::findExistingIdentities)
    }

    @Query(
        """
        SELECT volume_name, media_store_id, local_id, availability
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
