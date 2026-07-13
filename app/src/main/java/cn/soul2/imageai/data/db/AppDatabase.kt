package cn.soul2.imageai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import cn.soul2.imageai.data.db.dao.AppSettingDao
import cn.soul2.imageai.data.db.entity.AppSettingEntity

@Database(entities = [AppSettingEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appSettingDao(): AppSettingDao
}
