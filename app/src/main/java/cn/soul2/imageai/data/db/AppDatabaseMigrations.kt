package cn.soul2.imageai.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object AppDatabaseMigrations {
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `image` (
                    `local_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `volume_name` TEXT NOT NULL,
                    `media_store_id` INTEGER NOT NULL,
                    `content_uri` TEXT NOT NULL,
                    `display_name` TEXT NOT NULL,
                    `mime_type` TEXT NOT NULL,
                    `width` INTEGER NOT NULL,
                    `height` INTEGER NOT NULL,
                    `size_bytes` INTEGER NOT NULL,
                    `captured_at_epoch_millis` INTEGER,
                    `added_at_epoch_millis` INTEGER NOT NULL,
                    `modified_at_epoch_millis` INTEGER NOT NULL,
                    `sort_time_epoch_millis` INTEGER NOT NULL,
                    `bucket_id` INTEGER,
                    `bucket_name` TEXT,
                    `is_favorite` INTEGER NOT NULL,
                    `quick_fingerprint` TEXT NOT NULL,
                    `availability` TEXT NOT NULL,
                    `last_seen_sync_run_id` INTEGER,
                    `missing_candidate_since_epoch_millis` INTEGER,
                    `missing_observation_count` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_image_volume_name_media_store_id` " +
                    "ON `image` (`volume_name`, `media_store_id`)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_image_availability_sort_time_epoch_millis_media_store_id` " +
                    "ON `image` (`availability`, `sort_time_epoch_millis`, `media_store_id`)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_image_bucket_id_availability_sort_time_epoch_millis_media_store_id` " +
                    "ON `image` " +
                    "(`bucket_id`, `availability`, `sort_time_epoch_millis`, `media_store_id`)",
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `media_sync_checkpoint` (
                    `volume_name` TEXT NOT NULL,
                    `generation` INTEGER,
                    `media_store_version` TEXT,
                    `cursor_modified_at_epoch_millis` INTEGER,
                    `cursor_media_store_id` INTEGER,
                    `completed_at_epoch_millis` INTEGER,
                    `full_reconciliation_at_epoch_millis` INTEGER,
                    PRIMARY KEY(`volume_name`)
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `media_sync_run` (
                    `run_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `mode` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `current_volume_name` TEXT,
                    `discovered_count` INTEGER NOT NULL,
                    `indexed_count` INTEGER NOT NULL,
                    `unavailable_count` INTEGER NOT NULL,
                    `error_code` TEXT,
                    `error_message` TEXT,
                    `started_at_epoch_millis` INTEGER NOT NULL,
                    `updated_at_epoch_millis` INTEGER NOT NULL,
                    `completed_at_epoch_millis` INTEGER
                )
                """.trimIndent(),
            )
        }
    }
}
