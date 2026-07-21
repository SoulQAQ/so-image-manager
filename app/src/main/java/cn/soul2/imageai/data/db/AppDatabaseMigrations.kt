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

    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            listOf(
                "CREATE TABLE IF NOT EXISTS `search_document` (`rowid` INTEGER NOT NULL, `file_name` TEXT NOT NULL, `album` TEXT NOT NULL, `caption` TEXT NOT NULL, `tags` TEXT NOT NULL, `categories` TEXT NOT NULL, `search_tokens` TEXT NOT NULL, `media_text` TEXT NOT NULL, PRIMARY KEY(`rowid`), FOREIGN KEY(`rowid`) REFERENCES `image`(`local_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE VIRTUAL TABLE IF NOT EXISTS `search_document_fts` USING FTS4(`file_name` TEXT NOT NULL, `album` TEXT NOT NULL, `caption` TEXT NOT NULL, `tags` TEXT NOT NULL, `categories` TEXT NOT NULL, `search_tokens` TEXT NOT NULL, `media_text` TEXT NOT NULL, content=`search_document`)",
                "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_document_fts_BEFORE_UPDATE BEFORE UPDATE ON `search_document` BEGIN DELETE FROM `search_document_fts` WHERE `docid`=OLD.`rowid`; END",
                "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_document_fts_BEFORE_DELETE BEFORE DELETE ON `search_document` BEGIN DELETE FROM `search_document_fts` WHERE `docid`=OLD.`rowid`; END",
                "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_document_fts_AFTER_UPDATE AFTER UPDATE ON `search_document` BEGIN INSERT INTO `search_document_fts`(`docid`, `file_name`, `album`, `caption`, `tags`, `categories`, `search_tokens`, `media_text`) VALUES (NEW.`rowid`, NEW.`file_name`, NEW.`album`, NEW.`caption`, NEW.`tags`, NEW.`categories`, NEW.`search_tokens`, NEW.`media_text`); END",
                "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_document_fts_AFTER_INSERT AFTER INSERT ON `search_document` BEGIN INSERT INTO `search_document_fts`(`docid`, `file_name`, `album`, `caption`, `tags`, `categories`, `search_tokens`, `media_text`) VALUES (NEW.`rowid`, NEW.`file_name`, NEW.`album`, NEW.`caption`, NEW.`tags`, NEW.`categories`, NEW.`search_tokens`, NEW.`media_text`); END",
                "CREATE TABLE IF NOT EXISTS `search_term` (`term_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `normalized_key` TEXT NOT NULL, `display_value` TEXT NOT NULL, `unit_type` TEXT NOT NULL)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_search_term_normalized_key` ON `search_term` (`normalized_key`)",
                "CREATE TABLE IF NOT EXISTS `image_search_term` (`image_local_id` INTEGER NOT NULL, `term_id` INTEGER NOT NULL, `field_mask` INTEGER NOT NULL, `ownership` TEXT NOT NULL, `weight` REAL NOT NULL, PRIMARY KEY(`image_local_id`, `term_id`, `field_mask`), FOREIGN KEY(`image_local_id`) REFERENCES `image`(`local_id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`term_id`) REFERENCES `search_term`(`term_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_image_search_term_image_local_id_field_mask_weight` ON `image_search_term` (`image_local_id`, `field_mask`, `weight`)",
                "CREATE INDEX IF NOT EXISTS `index_image_search_term_term_id` ON `image_search_term` (`term_id`)",
                "CREATE TABLE IF NOT EXISTS `search_term_alias` (`alias_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `term_id` INTEGER NOT NULL, `alias_type` TEXT NOT NULL, `alias_text` TEXT NOT NULL, FOREIGN KEY(`term_id`) REFERENCES `search_term`(`term_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_search_term_alias_term_id_alias_type` ON `search_term_alias` (`term_id`, `alias_type`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_search_term_alias_term_id_alias_type_alias_text` ON `search_term_alias` (`term_id`, `alias_type`, `alias_text`)",
                "CREATE TABLE IF NOT EXISTS `search_source_chunk` (`chunk_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `image_local_id` INTEGER NOT NULL, `field` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `normalized_text` TEXT NOT NULL, FOREIGN KEY(`image_local_id`) REFERENCES `image`(`local_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_search_source_chunk_image_local_id_field_ordinal` ON `search_source_chunk` (`image_local_id`, `field`, `ordinal`)",
                "CREATE TABLE IF NOT EXISTS `search_text_alias_chunk` (`alias_chunk_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `image_local_id` INTEGER NOT NULL, `field` TEXT NOT NULL, `alias_type` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `alias_text` TEXT NOT NULL, FOREIGN KEY(`image_local_id`) REFERENCES `image`(`local_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_search_text_alias_chunk_image_local_id_field_alias_type_ordinal` ON `search_text_alias_chunk` (`image_local_id`, `field`, `alias_type`, `ordinal`)",
                "CREATE TABLE IF NOT EXISTS `search_gram` (`gram` TEXT NOT NULL, `owner_type` TEXT NOT NULL, `owner_id` INTEGER NOT NULL, PRIMARY KEY(`gram`, `owner_type`, `owner_id`))",
                "CREATE INDEX IF NOT EXISTS `index_search_gram_gram_owner_type` ON `search_gram` (`gram`, `owner_type`)",
            ).forEach(database::execSQL)
        }
    }

    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            listOf(
                """
                CREATE TABLE IF NOT EXISTS `provider_profile` (
                    `provider_id` TEXT NOT NULL,
                    `display_name` TEXT NOT NULL,
                    `base_url` TEXT NOT NULL,
                    `auth_mode` TEXT NOT NULL,
                    `auth_header_name` TEXT,
                    `auth_prefix` TEXT,
                    `credential_id` TEXT,
                    `headers_json` TEXT NOT NULL,
                    `allowed_redirect_origins_json` TEXT NOT NULL,
                    `cleartext_approved` INTEGER NOT NULL,
                    `connect_timeout_millis` INTEGER NOT NULL,
                    `read_timeout_millis` INTEGER NOT NULL,
                    `write_timeout_millis` INTEGER NOT NULL,
                    `max_concurrency` INTEGER NOT NULL,
                    `requests_per_minute` INTEGER NOT NULL,
                    `requests_per_day` INTEGER NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `created_at_epoch_millis` INTEGER NOT NULL,
                    `updated_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`provider_id`)
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `protocol_definition` (
                    `protocol_definition_id` TEXT NOT NULL,
                    `display_name` TEXT NOT NULL,
                    `definition_json` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `created_at_epoch_millis` INTEGER NOT NULL,
                    `updated_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`protocol_definition_id`)
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `model_profile` (
                    `model_profile_id` TEXT NOT NULL,
                    `provider_id` TEXT NOT NULL,
                    `display_name` TEXT NOT NULL,
                    `model_id` TEXT NOT NULL,
                    `protocol_type` TEXT NOT NULL,
                    `protocol_definition_id` TEXT,
                    `supports_vision` INTEGER NOT NULL,
                    `max_output_tokens` INTEGER,
                    `temperature` REAL,
                    `max_image_edge` INTEGER NOT NULL,
                    `max_image_bytes` INTEGER NOT NULL,
                    `max_concurrency` INTEGER NOT NULL,
                    `requests_per_minute` INTEGER NOT NULL,
                    `requests_per_day` INTEGER NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `created_at_epoch_millis` INTEGER NOT NULL,
                    `updated_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`model_profile_id`),
                    FOREIGN KEY(`provider_id`) REFERENCES `provider_profile`(`provider_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`protocol_definition_id`)
                        REFERENCES `protocol_definition`(`protocol_definition_id`)
                        ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS `index_model_profile_provider_id` " +
                    "ON `model_profile` (`provider_id`)",
                "CREATE INDEX IF NOT EXISTS `index_model_profile_protocol_definition_id` " +
                    "ON `model_profile` (`protocol_definition_id`)",
                """
                CREATE TABLE IF NOT EXISTS `ai_runtime_setting` (
                    `singleton_id` INTEGER NOT NULL,
                    `default_model_profile_id` TEXT,
                    `global_max_concurrency` INTEGER NOT NULL,
                    `global_requests_per_minute` INTEGER NOT NULL,
                    `global_requests_per_day` INTEGER NOT NULL,
                    `prompt_text` TEXT NOT NULL,
                    `updated_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`singleton_id`),
                    FOREIGN KEY(`default_model_profile_id`)
                        REFERENCES `model_profile`(`model_profile_id`)
                        ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS `index_ai_runtime_setting_default_model_profile_id` " +
                    "ON `ai_runtime_setting` (`default_model_profile_id`)",
            ).forEach(database::execSQL)
        }
    }

    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE `image` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'MEDIA_STORE'",
            )
        }
    }

    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            listOf(
                "CREATE TABLE IF NOT EXISTS `batch_analysis_run` (`run_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `state` TEXT NOT NULL, `total_count` INTEGER NOT NULL, `completed_count` INTEGER NOT NULL, `failed_count` INTEGER NOT NULL, `created_at_epoch_millis` INTEGER NOT NULL, `updated_at_epoch_millis` INTEGER NOT NULL, `completed_at_epoch_millis` INTEGER)",
                "CREATE TABLE IF NOT EXISTS `batch_analysis_item` (`run_id` INTEGER NOT NULL, `image_local_id` INTEGER NOT NULL, `state` TEXT NOT NULL, `failure_code` TEXT, PRIMARY KEY(`run_id`, `image_local_id`), FOREIGN KEY(`run_id`) REFERENCES `batch_analysis_run`(`run_id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`image_local_id`) REFERENCES `image`(`local_id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                "CREATE INDEX IF NOT EXISTS `index_batch_analysis_item_state_run_id` ON `batch_analysis_item` (`state`, `run_id`)",
                "CREATE INDEX IF NOT EXISTS `index_batch_analysis_item_image_local_id` ON `batch_analysis_item` (`image_local_id`)",
            ).forEach(database::execSQL)
        }
    }
}
