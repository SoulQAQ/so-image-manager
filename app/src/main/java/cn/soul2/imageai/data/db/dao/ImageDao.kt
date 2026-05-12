package cn.soul2.imageai.data.db.dao

import androidx.room.*
import cn.soul2.imageai.data.db.entity.ImageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(image: ImageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(images: List<ImageEntity>)

    @Update
    suspend fun update(image: ImageEntity)

    @Delete
    suspend fun delete(image: ImageEntity)

    @Query("DELETE FROM image WHERE id = :imageId")
    suspend fun deleteById(imageId: Long)

    @Query("SELECT * FROM image WHERE id = :imageId")
    suspend fun getById(imageId: Long): ImageEntity?

    @Query("SELECT * FROM image WHERE sha256 = :sha256")
    suspend fun getBySha256(sha256: String): ImageEntity?

    @Query("SELECT * FROM image WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): ImageEntity?

    @Query("SELECT * FROM image ORDER BY importTime DESC")
    fun getAllFlow(): Flow<List<ImageEntity>>

    @Query("SELECT * FROM image ORDER BY importTime DESC LIMIT :limit OFFSET :offset")
    suspend fun getPaged(limit: Int, offset: Int): List<ImageEntity>

    @Query("SELECT * FROM image WHERE aiStatus = :status ORDER BY importTime DESC")
    suspend fun getByAiStatus(status: Int): List<ImageEntity>

    @Query("SELECT * FROM image WHERE aiStatus = 0 ORDER BY importTime ASC LIMIT :limit")
    suspend fun getPendingForAi(limit: Int): List<ImageEntity>

    @Query("UPDATE image SET aiStatus = :status WHERE id = :imageId")
    suspend fun updateAiStatus(imageId: Long, status: Int)

    @Query("UPDATE image SET aiStatus = :status, aiModel = :model, promptVersion = :promptVersion, schemaVersion = :schemaVersion, analyzedAt = :analyzedAt WHERE id = :imageId")
    suspend fun updateAiResult(imageId: Long, status: Int, model: String, promptVersion: String, schemaVersion: String, analyzedAt: Long)

    @Query("SELECT COUNT(*) FROM image")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM image WHERE aiStatus = :status")
    suspend fun getCountByAiStatus(status: Int): Int

    @Transaction
    suspend fun insertOrUpdate(image: ImageEntity): Long {
        val existing = getBySha256(image.sha256)
        return if (existing != null) {
            existing.id
        } else {
            insert(image)
        }
    }
}
