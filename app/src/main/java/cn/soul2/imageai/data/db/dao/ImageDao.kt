package cn.soul2.imageai.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cn.soul2.imageai.data.db.entity.ImageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageDao {
    @Query("SELECT COUNT(*) FROM image WHERE availability = 'AVAILABLE'")
    fun observeAvailableCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM image
        WHERE availability = 'AVAILABLE'
        ORDER BY sort_time_epoch_millis DESC, media_store_id DESC
        """,
    )
    fun pagingAll(): PagingSource<Int, ImageEntity>

    @Query(
        """
        SELECT * FROM image
        WHERE availability = 'AVAILABLE'
        ORDER BY sort_time_epoch_millis DESC, media_store_id DESC
        """,
    )
    fun pagingRecent(): PagingSource<Int, ImageEntity>

    @Query("SELECT * FROM image WHERE local_id = :localId LIMIT 1")
    suspend fun getById(localId: Long): ImageEntity?

    @Upsert
    suspend fun upsert(images: List<ImageEntity>)
}
