package cn.soul2.imageai.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cn.soul2.imageai.data.db.entity.AppSettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppSettingDao {
    @Query("SELECT * FROM app_setting ORDER BY `key` ASC")
    suspend fun getAll(): List<AppSettingEntity>

    @Query("SELECT * FROM app_setting WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): AppSettingEntity?

    @Query("SELECT * FROM app_setting WHERE `key` = :key LIMIT 1")
    fun observeByKey(key: String): Flow<AppSettingEntity?>

    @Upsert
    suspend fun upsert(setting: AppSettingEntity)

    @Upsert
    suspend fun upsertAll(settings: List<AppSettingEntity>)

    @Query("DELETE FROM app_setting WHERE `key` = :key")
    suspend fun deleteByKey(key: String)
}
