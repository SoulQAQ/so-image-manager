package cn.soul2.imageai.data.db

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSchemaContractTest {
    @Test
    fun galleryAndNeighborQueriesUseATotalOrderAcrossVolumes() {
        val source = projectFile(
            "app/src/main/java/cn/soul2/imageai/data/db/dao/ImageDao.kt",
        ).readText()
        val descendingOrder = Regex(
            "ORDER BY\\s+sort_time_epoch_millis\\s+DESC,\\s*" +
                "media_store_id\\s+DESC,\\s*volume_name\\s+DESC,\\s*local_id\\s+DESC",
        )
        val ascendingOrder = Regex(
            "ORDER BY\\s+sort_time_epoch_millis\\s+ASC,\\s*" +
                "media_store_id\\s+ASC,\\s*volume_name\\s+ASC,\\s*local_id\\s+ASC",
        )

        assertEquals(
            "pagingAll, pagingRecent and the next-neighbor query must share the total order",
            3,
            descendingOrder.findAll(source).count(),
        )
        assertEquals(
            "the previous-neighbor query must reverse the complete total order",
            1,
            ascendingOrder.findAll(source).count(),
        )
    }

    @Test
    fun versionTwoExportsTheMediaIndexContract() {
        val schema = schemaFile(version = 2)
        assertTrue("Room schema v2 must be exported", schema.isFile)

        val text = schema.readText()
        assertTrue(Regex("\\\"version\\\"\\s*:\\s*2").containsMatchIn(text))
        assertEquals(
            setOf("app_setting", "image", "media_sync_checkpoint", "media_sync_run"),
            Regex("\\\"tableName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .findAll(text)
                .map { it.groupValues[1] }
                .toSet(),
        )

        val image = entityObject(text, "image")
        assertEquals(
            setOf(
                "local_id",
                "volume_name",
                "media_store_id",
                "content_uri",
                "display_name",
                "mime_type",
                "width",
                "height",
                "size_bytes",
                "captured_at_epoch_millis",
                "added_at_epoch_millis",
                "modified_at_epoch_millis",
                "sort_time_epoch_millis",
                "bucket_id",
                "bucket_name",
                "is_favorite",
                "quick_fingerprint",
                "availability",
                "last_seen_sync_run_id",
                "missing_candidate_since_epoch_millis",
                "missing_observation_count",
            ),
            columnNames(image),
        )
        assertEquals(
            setOf(
                "index_image_volume_name_media_store_id",
                "index_image_availability_sort_time_epoch_millis_media_store_id",
                "index_image_bucket_id_availability_sort_time_epoch_millis_media_store_id",
            ),
            indexNames(image),
        )
        assertTrue(
            Regex(
                "\\\"name\\\"\\s*:\\s*\\\"index_image_volume_name_media_store_id\\\"" +
                    "[\\s\\S]*?\\\"unique\\\"\\s*:\\s*true",
            ).containsMatchIn(image),
        )

        assertEquals(
            setOf(
                "volume_name",
                "generation",
                "media_store_version",
                "full_scan_cursor_modified_at_epoch_millis",
                "full_scan_cursor_media_store_id",
                "incremental_high_water_modified_at_epoch_millis",
                "incremental_high_water_media_store_id",
                "completed_at_epoch_millis",
                "full_reconciliation_at_epoch_millis",
            ),
            columnNames(entityObject(text, "media_sync_checkpoint")),
        )
        assertEquals(
            setOf(
                "run_id",
                "mode",
                "state",
                "current_volume_name",
                "discovered_count",
                "indexed_count",
                "unavailable_count",
                "error_code",
                "error_message",
                "started_at_epoch_millis",
                "updated_at_epoch_millis",
                "completed_at_epoch_millis",
            ),
            columnNames(entityObject(text, "media_sync_run")),
        )

        listOf("image_fts", "fts5", "image_ai", "image_feature", "image_query_cache")
            .forEach { forbidden ->
                assertFalse("Out-of-scope schema term remains: $forbidden", text.contains(forbidden))
            }
    }

    @Test
    fun versionThreeExportsCanonicalProjectionWithoutPhaseThreeSearchTables() {
        val schema = schemaFile(version = 3)
        assertTrue("Room schema v3 must be exported", schema.isFile)

        val text = schema.readText()
        assertTrue(Regex("\\\"version\\\"\\s*:\\s*3").containsMatchIn(text))
        assertEquals(
            setOf(
                "app_setting",
                "image",
                "media_sync_checkpoint",
                "media_sync_run",
                "image_analysis",
                "analysis_term",
                "active_image_analysis",
                "image_user_correction",
                "user_term_override",
                "effective_image_metadata",
                "effective_image_term",
                "analysis_activation_diagnostic",
            ),
            Regex("\\\"tableName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .findAll(text)
                .map { it.groupValues[1] }
                .toSet(),
        )

        val active = entityObject(text, "active_image_analysis")
        assertTrue(active.contains("analysis_id"))
        assertTrue(active.contains("image_local_id"))
        assertTrue(active.contains("ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED"))
        assertTrue(active.contains("ON UPDATE NO ACTION ON DELETE CASCADE"))

        val effectiveTerms = entityObject(text, "effective_image_term")
        assertTrue(effectiveTerms.contains("source_analysis_id"))
        assertTrue(effectiveTerms.contains("ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED"))

        listOf(
            "search_document",
            "image_search_term",
            "search_term_alias",
            "search_text_alias_chunk",
            "search_gram",
            "fts4",
            "fts5",
        ).forEach { forbidden ->
            assertFalse("Phase 3 schema leaked into v3: $forbidden", text.contains(forbidden, true))
        }
    }

    @Test
    fun migrationOneToTwoCreatesSeparatedFullScanAndIncrementalCursors() {
        val source = projectFile(
            "app/src/main/java/cn/soul2/imageai/data/db/AppDatabaseMigrations.kt",
        ).readText()

        listOf(
            "full_scan_cursor_modified_at_epoch_millis",
            "full_scan_cursor_media_store_id",
            "incremental_high_water_modified_at_epoch_millis",
            "incremental_high_water_media_store_id",
        ).forEach { column ->
            assertTrue("MIGRATION_1_2 must create $column", source.contains("`$column` INTEGER"))
        }
        assertFalse(source.contains("`cursor_modified_at_epoch_millis` INTEGER"))
        assertFalse(source.contains("`cursor_media_store_id` INTEGER"))
    }

    private fun schemaFile(version: Int): File {
        return projectFile(
            "app/schemas/cn.soul2.imageai.data.db.AppDatabase/$version.json",
        )
    }

    private fun projectFile(path: String): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(workingDirectory).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        return File(root, path)
    }

    private fun columnNames(entityObject: String): Set<String> =
        Regex("\\\"columnName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .findAll(entityObject)
            .map { it.groupValues[1] }
            .toSet()

    private fun indexNames(entityObject: String): Set<String> =
        Regex("\\\"name\\\"\\s*:\\s*\\\"(index_[^\\\"]+)\\\"")
            .findAll(entityObject)
            .map { it.groupValues[1] }
            .toSet()

    private fun entityObject(text: String, tableName: String): String {
        val marker = "\"tableName\": \"$tableName\""
        val markerStart = text.indexOf(marker)
        require(markerStart >= 0) { "Missing entity: $tableName" }
        val objectStart = text.lastIndexOf('{', markerStart)
        var depth = 0
        var inString = false
        var escaped = false
        text.forEachIndexed { index, character ->
            if (index < objectStart) return@forEachIndexed
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(objectStart, index + 1)
                    }
                }
            }
        }
        error("Unterminated entity object: $tableName")
    }
}
