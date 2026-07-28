package cn.soul2.imageai.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.AnalysisTermKind
import cn.soul2.imageai.gallery.GalleryCollectionSummary
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ImageDao {
    @Query(
        """
        SELECT COUNT(*) FROM image
        WHERE availability = 'AVAILABLE' AND partition = 'MAIN' AND missing_candidate_since_epoch_millis IS NULL
        """,
    )
    abstract fun observeAvailableCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM image WHERE availability = 'AVAILABLE' AND missing_candidate_since_epoch_millis IS NULL")
    abstract fun observeTotalGalleryCount(): Flow<Int>

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

    @Query("UPDATE image SET partition = 'PRIVATE' WHERE local_id = :localId")
    protected abstract suspend fun moveToPrivate(localId: Long): Int

    @Query("UPDATE image SET partition = 'PRIVATE' WHERE local_id IN (:localIds) AND partition = 'MAIN' AND availability = 'AVAILABLE'")
    protected abstract suspend fun moveMainImagesToPrivateInternal(localIds: List<Long>): Int

    @Transaction
    open suspend fun moveMainImagesToPrivate(localIds: Collection<Long>): Int {
        val ids = localIds.distinct().filter { it > 0L }
        if (ids.isEmpty()) return 0
        deleteSearchDocuments(ids)
        return moveMainImagesToPrivateInternal(ids)
    }

    @Query("UPDATE image SET partition = 'MAIN' WHERE local_id = :localId AND partition IN ('PRIVATE', 'PRIVATE_UNANALYZABLE')")
    abstract suspend fun moveToMain(localId: Long): Int

    @Query("SELECT COUNT(*) FROM image WHERE availability = 'AVAILABLE' AND partition = 'MAIN' AND missing_candidate_since_epoch_millis IS NULL")
    abstract fun observeMainCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM image WHERE availability = 'AVAILABLE' AND partition = 'UNPROCESSED' AND missing_candidate_since_epoch_millis IS NULL")
    abstract fun observeUnprocessedCount(): Flow<Int>

    @Query(
        """
        SELECT
            CASE WHEN image.bucket_id IS NULL
                THEN 'name:' || COALESCE(image.bucket_name, '')
                ELSE CAST(image.bucket_id AS TEXT)
            END AS collection_key,
            COALESCE(NULLIF(image.bucket_name, ''), '未命名相册') AS display_name,
            COUNT(*) AS image_count,
            (
                SELECT cover.content_uri FROM image AS cover
                WHERE cover.availability = 'AVAILABLE'
                  AND cover.partition = 'MAIN'
                  AND cover.missing_candidate_since_epoch_millis IS NULL
                  AND (
                      (image.bucket_id IS NOT NULL AND cover.bucket_id = image.bucket_id)
                      OR (
                          image.bucket_id IS NULL AND cover.bucket_id IS NULL
                          AND COALESCE(cover.bucket_name, '') = COALESCE(image.bucket_name, '')
                      )
                  )
                ORDER BY cover.sort_time_epoch_millis DESC, cover.media_store_id DESC,
                    cover.volume_name DESC, cover.local_id DESC
                LIMIT 1
            ) AS cover_uri
        FROM image
        WHERE image.availability = 'AVAILABLE' AND image.partition = 'MAIN'
          AND image.missing_candidate_since_epoch_millis IS NULL
        GROUP BY image.bucket_id, image.bucket_name
        ORDER BY MAX(image.sort_time_epoch_millis) DESC, display_name COLLATE NOCASE ASC
        """,
    )
    abstract fun observeAlbumCollections(): Flow<List<GalleryCollectionSummary>>

    @Query(
        """
        SELECT
            term.normalized_key AS collection_key,
            MAX(term.display_value) AS display_name,
            COUNT(*) AS image_count,
            (
                SELECT cover.content_uri
                FROM effective_image_term AS cover_term
                INNER JOIN image AS cover ON cover.local_id = cover_term.image_local_id
                WHERE cover_term.kind = :kind
                  AND cover_term.normalized_key = term.normalized_key
                  AND cover.availability = 'AVAILABLE'
                  AND cover.partition = 'MAIN'
                  AND cover.missing_candidate_since_epoch_millis IS NULL
                ORDER BY cover.sort_time_epoch_millis DESC, cover.media_store_id DESC,
                    cover.volume_name DESC, cover.local_id DESC
                LIMIT 1
            ) AS cover_uri
        FROM effective_image_term AS term
        INNER JOIN image ON image.local_id = term.image_local_id
        WHERE term.kind = :kind
          AND image.availability = 'AVAILABLE' AND image.partition = 'MAIN'
          AND image.missing_candidate_since_epoch_millis IS NULL
        GROUP BY term.normalized_key
        ORDER BY image_count DESC, display_name COLLATE NOCASE ASC
        """,
    )
    abstract fun observeTermCollections(kind: AnalysisTermKind): Flow<List<GalleryCollectionSummary>>

    @Query("UPDATE image SET partition = 'MAIN' WHERE local_id = :localId AND partition = 'UNPROCESSED'")
    abstract suspend fun promoteUnprocessedToMain(localId: Long): Int

    @Query("UPDATE image SET partition = 'PRIVATE_UNANALYZABLE' WHERE local_id = :localId")
    protected abstract suspend fun markPrivateUnanalyzable(localId: Long): Int

    @Transaction
    open suspend fun moveRejectedMainImageToPrivate(localId: Long): Int {
        deleteSearchDocuments(listOf(localId))
        return moveToPrivate(localId)
    }

    @Transaction
    open suspend fun markRejectedPrivateImageUnanalyzable(localId: Long): Int {
        deleteSearchDocuments(listOf(localId))
        return markPrivateUnanalyzable(localId)
    }

    @Query("SELECT * FROM image WHERE local_id IN (:localIds) AND availability = 'AVAILABLE'")
    abstract suspend fun availableByIds(localIds: List<Long>): List<ImageEntity>

    @Query("SELECT local_id FROM image WHERE availability = 'AVAILABLE' AND partition = 'UNPROCESSED' AND missing_candidate_since_epoch_millis IS NULL")
    abstract suspend fun allAvailableIds(): List<Long>

    @Query(
        """
        SELECT image.local_id FROM image
        LEFT JOIN active_image_analysis AS active ON active.image_local_id = image.local_id
        WHERE image.availability = 'AVAILABLE' AND image.partition = 'UNPROCESSED'
          AND image.missing_candidate_since_epoch_millis IS NULL
          AND active.analysis_id IS NULL
        """,
    )
    abstract suspend fun allUnanalyzedAvailableIds(): List<Long>

    @Query(
        """
        SELECT * FROM image
        WHERE image.availability = 'AVAILABLE' AND image.partition = 'MAIN' AND image.missing_candidate_since_epoch_millis IS NULL
        ORDER BY sort_time_epoch_millis DESC, media_store_id DESC,
            volume_name DESC, local_id DESC
        """,
    )
    abstract fun pagingAll(): PagingSource<Int, ImageEntity>

    @Query(
        """
        SELECT * FROM image
        WHERE image.availability = 'AVAILABLE' AND image.partition = 'MAIN' AND image.missing_candidate_since_epoch_millis IS NULL
        ORDER BY sort_time_epoch_millis DESC, media_store_id DESC,
            volume_name DESC, local_id DESC
        """,
    )
    abstract fun pagingRecent(): PagingSource<Int, ImageEntity>

    @Query(
        """
        SELECT image.* FROM image
        INNER JOIN active_image_analysis AS active ON active.image_local_id = image.local_id
        WHERE image.availability = 'AVAILABLE' AND image.partition = 'MAIN' AND image.missing_candidate_since_epoch_millis IS NULL
        ORDER BY image.sort_time_epoch_millis DESC, image.media_store_id DESC,
            image.volume_name DESC, image.local_id DESC
        """,
    )
    abstract fun pagingAnalyzed(): PagingSource<Int, ImageEntity>

    @Query(
        """
        SELECT image.* FROM image
        LEFT JOIN active_image_analysis AS active ON active.image_local_id = image.local_id
        WHERE image.availability = 'AVAILABLE' AND image.partition = 'UNPROCESSED' AND image.missing_candidate_since_epoch_millis IS NULL
          AND active.analysis_id IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM batch_analysis_item AS batch_item
              INNER JOIN batch_analysis_run AS batch_run ON batch_run.run_id = batch_item.run_id
              WHERE batch_item.image_local_id = image.local_id
                AND batch_item.state IN ('QUEUED', 'RUNNING')
                AND batch_run.state IN ('QUEUED', 'RUNNING')
          )
        ORDER BY image.sort_time_epoch_millis DESC, image.media_store_id DESC,
            image.volume_name DESC, image.local_id DESC
        """,
    )
    abstract fun pagingUnanalyzed(): PagingSource<Int, ImageEntity>

    @Query(
        """
        SELECT * FROM image
        WHERE availability = 'AVAILABLE' AND partition = 'MAIN'
          AND missing_candidate_since_epoch_millis IS NULL
          AND (
              (:bucketId IS NOT NULL AND bucket_id = :bucketId)
              OR (
                  :bucketId IS NULL AND bucket_id IS NULL
                  AND COALESCE(bucket_name, '') = :bucketName
              )
          )
        ORDER BY sort_time_epoch_millis DESC, media_store_id DESC,
            volume_name DESC, local_id DESC
        """,
    )
    abstract fun pagingAlbum(bucketId: Long?, bucketName: String): PagingSource<Int, ImageEntity>

    @Query(
        """
        SELECT image.* FROM image
        INNER JOIN effective_image_term AS term ON term.image_local_id = image.local_id
        WHERE term.kind = :kind AND term.normalized_key = :normalizedKey
          AND image.availability = 'AVAILABLE' AND image.partition = 'MAIN'
          AND image.missing_candidate_since_epoch_millis IS NULL
        ORDER BY image.sort_time_epoch_millis DESC, image.media_store_id DESC,
            image.volume_name DESC, image.local_id DESC
        """,
    )
    abstract fun pagingTerm(
        kind: AnalysisTermKind,
        normalizedKey: String,
    ): PagingSource<Int, ImageEntity>

    @Query(
        """
        SELECT * FROM image
        WHERE local_id = :localId
          AND availability = 'AVAILABLE' AND partition = 'MAIN'
          AND missing_candidate_since_epoch_millis IS NULL
          AND (
              (:bucketId IS NOT NULL AND bucket_id = :bucketId)
              OR (
                  :bucketId IS NULL AND bucket_id IS NULL
                  AND COALESCE(bucket_name, '') = :bucketName
              )
          )
        LIMIT 1
        """,
    )
    abstract fun observeAlbumImage(
        localId: Long,
        bucketId: Long?,
        bucketName: String,
    ): Flow<ImageEntity?>

    @Query(
        """
        SELECT * FROM image
        WHERE availability = 'AVAILABLE' AND partition = 'MAIN'
          AND missing_candidate_since_epoch_millis IS NULL
          AND (
              (:bucketId IS NOT NULL AND bucket_id = :bucketId)
              OR (
                  :bucketId IS NULL AND bucket_id IS NULL
                  AND COALESCE(bucket_name, '') = :bucketName
              )
          )
          AND (sort_time_epoch_millis > :sortTime
              OR (sort_time_epoch_millis = :sortTime AND media_store_id > :mediaStoreId)
              OR (sort_time_epoch_millis = :sortTime AND media_store_id = :mediaStoreId AND volume_name > :volumeName)
              OR (sort_time_epoch_millis = :sortTime AND media_store_id = :mediaStoreId AND volume_name = :volumeName AND local_id > :localId))
        ORDER BY sort_time_epoch_millis ASC, media_store_id ASC,
            volume_name ASC, local_id ASC
        LIMIT 1
        """,
    )
    abstract fun observePreviousInAlbum(
        sortTime: Long,
        mediaStoreId: Long,
        volumeName: String,
        localId: Long,
        bucketId: Long?,
        bucketName: String,
    ): Flow<ImageEntity?>

    @Query(
        """
        SELECT * FROM image
        WHERE availability = 'AVAILABLE' AND partition = 'MAIN'
          AND missing_candidate_since_epoch_millis IS NULL
          AND (
              (:bucketId IS NOT NULL AND bucket_id = :bucketId)
              OR (
                  :bucketId IS NULL AND bucket_id IS NULL
                  AND COALESCE(bucket_name, '') = :bucketName
              )
          )
          AND (sort_time_epoch_millis < :sortTime
              OR (sort_time_epoch_millis = :sortTime AND media_store_id < :mediaStoreId)
              OR (sort_time_epoch_millis = :sortTime AND media_store_id = :mediaStoreId AND volume_name < :volumeName)
              OR (sort_time_epoch_millis = :sortTime AND media_store_id = :mediaStoreId AND volume_name = :volumeName AND local_id < :localId))
        ORDER BY sort_time_epoch_millis DESC, media_store_id DESC,
            volume_name DESC, local_id DESC
        LIMIT 1
        """,
    )
    abstract fun observeNextInAlbum(
        sortTime: Long,
        mediaStoreId: Long,
        volumeName: String,
        localId: Long,
        bucketId: Long?,
        bucketName: String,
    ): Flow<ImageEntity?>

    @Query(
        """
        SELECT image.* FROM image
        INNER JOIN effective_image_term AS term ON term.image_local_id = image.local_id
        WHERE image.local_id = :localId
          AND term.kind = :kind AND term.normalized_key = :normalizedKey
          AND image.availability = 'AVAILABLE' AND image.partition = 'MAIN'
          AND image.missing_candidate_since_epoch_millis IS NULL
        LIMIT 1
        """,
    )
    abstract fun observeTermImage(
        localId: Long,
        kind: AnalysisTermKind,
        normalizedKey: String,
    ): Flow<ImageEntity?>

    @Query(
        """
        SELECT image.* FROM image
        INNER JOIN effective_image_term AS term ON term.image_local_id = image.local_id
        WHERE term.kind = :kind AND term.normalized_key = :normalizedKey
          AND image.availability = 'AVAILABLE' AND image.partition = 'MAIN'
          AND image.missing_candidate_since_epoch_millis IS NULL
          AND (image.sort_time_epoch_millis > :sortTime
              OR (image.sort_time_epoch_millis = :sortTime AND image.media_store_id > :mediaStoreId)
              OR (image.sort_time_epoch_millis = :sortTime AND image.media_store_id = :mediaStoreId AND image.volume_name > :volumeName)
              OR (image.sort_time_epoch_millis = :sortTime AND image.media_store_id = :mediaStoreId AND image.volume_name = :volumeName AND image.local_id > :localId))
        ORDER BY image.sort_time_epoch_millis ASC, image.media_store_id ASC,
            image.volume_name ASC, image.local_id ASC
        LIMIT 1
        """,
    )
    abstract fun observePreviousInTerm(
        sortTime: Long,
        mediaStoreId: Long,
        volumeName: String,
        localId: Long,
        kind: AnalysisTermKind,
        normalizedKey: String,
    ): Flow<ImageEntity?>

    @Query(
        """
        SELECT image.* FROM image
        INNER JOIN effective_image_term AS term ON term.image_local_id = image.local_id
        WHERE term.kind = :kind AND term.normalized_key = :normalizedKey
          AND image.availability = 'AVAILABLE' AND image.partition = 'MAIN'
          AND image.missing_candidate_since_epoch_millis IS NULL
          AND (image.sort_time_epoch_millis < :sortTime
              OR (image.sort_time_epoch_millis = :sortTime AND image.media_store_id < :mediaStoreId)
              OR (image.sort_time_epoch_millis = :sortTime AND image.media_store_id = :mediaStoreId AND image.volume_name < :volumeName)
              OR (image.sort_time_epoch_millis = :sortTime AND image.media_store_id = :mediaStoreId AND image.volume_name = :volumeName AND image.local_id < :localId))
        ORDER BY image.sort_time_epoch_millis DESC, image.media_store_id DESC,
            image.volume_name DESC, image.local_id DESC
        LIMIT 1
        """,
    )
    abstract fun observeNextInTerm(
        sortTime: Long,
        mediaStoreId: Long,
        volumeName: String,
        localId: Long,
        kind: AnalysisTermKind,
        normalizedKey: String,
    ): Flow<ImageEntity?>

    @Query(
        """
        SELECT * FROM image
        WHERE image.availability = 'AVAILABLE' AND image.partition = 'PRIVATE'
          AND image.missing_candidate_since_epoch_millis IS NULL
        ORDER BY image.sort_time_epoch_millis DESC, image.media_store_id DESC,
            image.volume_name DESC, image.local_id DESC
        """,
    )
    abstract fun pagingPrivate(): PagingSource<Int, ImageEntity>

    @Query(
        """
        SELECT * FROM image
        WHERE image.partition = 'PRIVATE_UNANALYZABLE'
        ORDER BY image.sort_time_epoch_millis DESC, image.media_store_id DESC,
            image.volume_name DESC, image.local_id DESC
        """,
    )
    abstract fun pagingRejected(): PagingSource<Int, ImageEntity>

    @Query(
        """
        SELECT * FROM image
        WHERE image.availability = 'AVAILABLE' AND image.partition = 'PRIVATE_UNANALYZABLE'
          AND image.missing_candidate_since_epoch_millis IS NULL
        ORDER BY image.sort_time_epoch_millis DESC, image.media_store_id DESC,
            image.volume_name DESC, image.local_id DESC
        """,
    )
    abstract fun pagingPrivateUnanalyzable(): PagingSource<Int, ImageEntity>

    @Query(
        "SELECT * FROM image WHERE local_id = :localId AND availability = 'AVAILABLE' AND partition = :partition AND missing_candidate_since_epoch_millis IS NULL LIMIT 1",
    )
    abstract fun observeAvailableByIdInPartition(localId: Long, partition: cn.soul2.imageai.data.db.entity.ImagePartition): Flow<ImageEntity?>

    @Query(
        "SELECT * FROM image WHERE availability = 'AVAILABLE' AND partition = :partition AND missing_candidate_since_epoch_millis IS NULL AND (sort_time_epoch_millis > :sortTime OR (sort_time_epoch_millis = :sortTime AND media_store_id > :mediaStoreId) OR (sort_time_epoch_millis = :sortTime AND media_store_id = :mediaStoreId AND volume_name > :volumeName) OR (sort_time_epoch_millis = :sortTime AND media_store_id = :mediaStoreId AND volume_name = :volumeName AND local_id > :localId)) ORDER BY sort_time_epoch_millis ASC, media_store_id ASC, volume_name ASC, local_id ASC LIMIT 1",
    )
    abstract fun observePreviousInPartition(sortTime: Long, mediaStoreId: Long, volumeName: String, localId: Long, partition: cn.soul2.imageai.data.db.entity.ImagePartition): Flow<ImageEntity?>

    @Query(
        "SELECT * FROM image WHERE availability = 'AVAILABLE' AND partition = :partition AND missing_candidate_since_epoch_millis IS NULL AND (sort_time_epoch_millis < :sortTime OR (sort_time_epoch_millis = :sortTime AND media_store_id < :mediaStoreId) OR (sort_time_epoch_millis = :sortTime AND media_store_id = :mediaStoreId AND volume_name < :volumeName) OR (sort_time_epoch_millis = :sortTime AND media_store_id = :mediaStoreId AND volume_name = :volumeName AND local_id < :localId)) ORDER BY sort_time_epoch_millis DESC, media_store_id DESC, volume_name DESC, local_id DESC LIMIT 1",
    )
    abstract fun observeNextInPartition(sortTime: Long, mediaStoreId: Long, volumeName: String, localId: Long, partition: cn.soul2.imageai.data.db.entity.ImagePartition): Flow<ImageEntity?>

    @Query(
        """
        SELECT * FROM image
        WHERE local_id = :localId
          AND availability = 'AVAILABLE' AND partition = 'MAIN'
          AND missing_candidate_since_epoch_millis IS NULL
        LIMIT 1
        """,
    )
    abstract fun observeAvailableById(localId: Long): Flow<ImageEntity?>

    @Query(
        """
        SELECT * FROM image
        WHERE availability = 'AVAILABLE' AND partition = 'MAIN'
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
        WHERE availability = 'AVAILABLE' AND partition = 'MAIN'
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
        SELECT volume_name, media_store_id, local_id, availability, partition
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
