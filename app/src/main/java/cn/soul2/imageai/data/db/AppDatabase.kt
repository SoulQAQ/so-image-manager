package cn.soul2.imageai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import cn.soul2.imageai.data.db.dao.AppSettingDao
import cn.soul2.imageai.data.db.dao.ImageDao
import cn.soul2.imageai.data.db.dao.MediaSyncDao
import cn.soul2.imageai.data.db.entity.AppSettingEntity
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.MediaSyncCheckpointEntity
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity

@Database(
    entities = [
        AppSettingEntity::class,
        ImageEntity::class,
        MediaSyncCheckpointEntity::class,
        MediaSyncRunEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appSettingDao(): AppSettingDao
    abstract fun imageDao(): ImageDao
    abstract fun mediaSyncDao(): MediaSyncDao
}
