package cn.soul2.imageai.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cn.soul2.imageai.data.db.entity.AppSettingEntity

@Dao
interface AppSettingDao {
    @Query("SELECT * FROM app_setting WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): AppSettingEntity?

    @Upsert
    suspend fun upsert(setting: AppSettingEntity)

    @Query("DELETE FROM app_setting WHERE `key` = :key")
    suspend fun deleteByKey(key: String)
}
