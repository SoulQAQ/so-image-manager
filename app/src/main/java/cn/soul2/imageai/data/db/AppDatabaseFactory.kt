package cn.soul2.imageai.data.db

import android.content.Context
import androidx.room.Room
import cn.soul2.imageai.data.db.AppDatabaseMigrations.MIGRATION_1_2
import cn.soul2.imageai.data.db.AppDatabaseMigrations.MIGRATION_2_3
import cn.soul2.imageai.data.db.AppDatabaseMigrations.MIGRATION_3_4
import cn.soul2.imageai.data.db.AppDatabaseMigrations.MIGRATION_4_5
import cn.soul2.imageai.data.db.AppDatabaseMigrations.MIGRATION_5_6
import cn.soul2.imageai.data.db.entity.AppSettingEntity

object AppDatabaseFactory {
    private const val DATABASE_NAME = "so_image_manager.db"
    private const val LEGACY_DATABASE_NAME = "image_ai.db"
    private const val LEGACY_CLEANUP_MARKER = "maintenance.legacy_database_cleaned"

    fun create(context: Context): AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        DATABASE_NAME,
    ).addMigrations(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
    ).build()

    suspend fun cleanupLegacyDatabaseIfNeeded(
        context: Context,
        database: AppDatabase,
    ) {
        val settings = database.appSettingDao()
        if (settings.getByKey(LEGACY_CLEANUP_MARKER) != null) return

        val applicationContext = context.applicationContext
        val legacyDatabase = applicationContext.getDatabasePath(LEGACY_DATABASE_NAME)
        if (legacyDatabase.exists()) {
            val deleted = applicationContext.deleteDatabase(LEGACY_DATABASE_NAME)
            check(deleted || !legacyDatabase.exists()) {
                "Unable to delete obsolete database: $LEGACY_DATABASE_NAME"
            }
        }

        settings.upsert(
            AppSettingEntity(
                key = LEGACY_CLEANUP_MARKER,
                valueJson = "true",
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }
}
