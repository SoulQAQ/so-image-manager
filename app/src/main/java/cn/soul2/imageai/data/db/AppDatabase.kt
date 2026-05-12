package cn.soul2.imageai.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.soul2.imageai.data.db.dao.*
import cn.soul2.imageai.data.db.entity.*

@Database(
    entities = [
        ImageEntity::class,
        ImageAiEntity::class,
        TagEntity::class,
        ImageTagEntity::class,
        TagAliasEntity::class,
        ImageQueryCacheEntity::class,
        ImageFeatureEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun imageDao(): ImageDao
    abstract fun imageAiDao(): ImageAiDao
    abstract fun tagDao(): TagDao
    abstract fun imageTagDao(): ImageTagDao
    abstract fun searchDao(): SearchDao
    abstract fun imageFeatureDao(): ImageFeatureDao

    companion object {
        private const val DATABASE_NAME = "image_ai.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // 创建 FTS5 虚拟表
                        db.execSQL("""
                            CREATE VIRTUAL TABLE IF NOT EXISTS image_fts
                            USING fts5(
                                imageId UNINDEXED,
                                content,
                                tokenize='unicode61'
                            )
                        """)
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        // 确保 FTS5 表存在（用于后续版本升级）
                        db.execSQL("""
                            CREATE VIRTUAL TABLE IF NOT EXISTS image_fts
                            USING fts5(
                                imageId UNINDEXED,
                                content,
                                tokenize='unicode61'
                            )
                        """)
                    }
                })
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
        }
    }
}
