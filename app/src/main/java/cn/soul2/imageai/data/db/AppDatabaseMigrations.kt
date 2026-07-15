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
                    `full_scan_cursor_modified_at_epoch_millis` INTEGER,
                    `full_scan_cursor_media_store_id` INTEGER,
                    `incremental_high_water_modified_at_epoch_millis` INTEGER,
                    `incremental_high_water_media_store_id` INTEGER,
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

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            listOf(
                """
                CREATE TABLE IF NOT EXISTS `image_analysis` (
                    `analysis_id` TEXT NOT NULL,
                    `image_local_id` INTEGER NOT NULL,
                    `schema_version` INTEGER NOT NULL,
                    `caption` TEXT NOT NULL,
                    `extension_json` TEXT,
                    `content_hash` TEXT NOT NULL,
                    `provider_profile_id` TEXT NOT NULL,
                    `model_profile_id` TEXT NOT NULL,
                    `protocol_definition_id` TEXT NOT NULL,
                    `prompt_template_id` TEXT NOT NULL,
                    `created_at_epoch_millis` INTEGER NOT NULL,
                    `completed_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`analysis_id`),
                    FOREIGN KEY(`image_local_id`) REFERENCES `image`(`local_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_image_analysis_image_local_id_completed_at_epoch_millis` " +
                    "ON `image_analysis` (`image_local_id`, `completed_at_epoch_millis`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_image_analysis_analysis_id_image_local_id` " +
                    "ON `image_analysis` (`analysis_id`, `image_local_id`)",
                """
                CREATE TABLE IF NOT EXISTS `analysis_term` (
                    `analysis_id` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `normalized_key` TEXT NOT NULL,
                    `display_value` TEXT NOT NULL,
                    `confidence` REAL,
                    PRIMARY KEY(`analysis_id`, `kind`, `normalized_key`),
                    FOREIGN KEY(`analysis_id`) REFERENCES `image_analysis`(`analysis_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS `index_analysis_term_analysis_id` " +
                    "ON `analysis_term` (`analysis_id`)",
                """
                CREATE TABLE IF NOT EXISTS `active_image_analysis` (
                    `image_local_id` INTEGER NOT NULL,
                    `analysis_id` TEXT NOT NULL,
                    PRIMARY KEY(`image_local_id`),
                    FOREIGN KEY(`image_local_id`) REFERENCES `image`(`local_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`analysis_id`, `image_local_id`)
                        REFERENCES `image_analysis`(`analysis_id`, `image_local_id`)
                        ON UPDATE NO ACTION ON DELETE NO ACTION
                        DEFERRABLE INITIALLY DEFERRED
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_active_image_analysis_analysis_id_image_local_id` " +
                    "ON `active_image_analysis` (`analysis_id`, `image_local_id`)",
                """
                CREATE TABLE IF NOT EXISTS `image_user_correction` (
                    `image_local_id` INTEGER NOT NULL,
                    `caption_mode` TEXT NOT NULL,
                    `caption_value` TEXT,
                    `revision` INTEGER NOT NULL,
                    `updated_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`image_local_id`),
                    FOREIGN KEY(`image_local_id`) REFERENCES `image`(`local_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `user_term_override` (
                    `image_local_id` INTEGER NOT NULL,
                    `kind` TEXT NOT NULL,
                    `normalized_key` TEXT NOT NULL,
                    `action` TEXT NOT NULL,
                    `display_value` TEXT,
                    `revision` INTEGER NOT NULL,
                    `updated_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`image_local_id`, `kind`, `normalized_key`),
                    FOREIGN KEY(`image_local_id`) REFERENCES `image`(`local_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS `index_user_term_override_image_local_id` " +
                    "ON `user_term_override` (`image_local_id`)",
                """
                CREATE TABLE IF NOT EXISTS `effective_image_metadata` (
                    `image_local_id` INTEGER NOT NULL,
                    `caption` TEXT,
                    `caption_source` TEXT NOT NULL,
                    `projection_generation` INTEGER NOT NULL,
                    `updated_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`image_local_id`),
                    FOREIGN KEY(`image_local_id`) REFERENCES `image`(`local_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `effective_image_term` (
                    `image_local_id` INTEGER NOT NULL,
                    `kind` TEXT NOT NULL,
                    `normalized_key` TEXT NOT NULL,
                    `display_value` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `confidence` REAL,
                    `source_analysis_id` TEXT,
                    PRIMARY KEY(`image_local_id`, `kind`, `normalized_key`),
                    FOREIGN KEY(`image_local_id`) REFERENCES `image`(`local_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`source_analysis_id`, `image_local_id`)
                        REFERENCES `image_analysis`(`analysis_id`, `image_local_id`)
                        ON UPDATE NO ACTION ON DELETE NO ACTION
                        DEFERRABLE INITIALLY DEFERRED
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_effective_image_term_kind_normalized_key_image_local_id` " +
                    "ON `effective_image_term` (`kind`, `normalized_key`, `image_local_id`)",
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_effective_image_term_source_analysis_id_image_local_id` " +
                    "ON `effective_image_term` (`source_analysis_id`, `image_local_id`)",
                """
                CREATE TABLE IF NOT EXISTS `analysis_activation_diagnostic` (
                    `analysis_id` TEXT NOT NULL,
                    `code` TEXT NOT NULL,
                    `detail` TEXT,
                    `updated_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`analysis_id`),
                    FOREIGN KEY(`analysis_id`) REFERENCES `image_analysis`(`analysis_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            ).forEach(database::execSQL)
        }
    }
}
