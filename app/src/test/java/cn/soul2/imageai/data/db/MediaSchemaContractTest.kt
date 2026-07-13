package cn.soul2.imageai.data.db

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSchemaContractTest {
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
                "cursor_modified_at_epoch_millis",
                "cursor_media_store_id",
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

    private fun schemaFile(version: Int): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(workingDirectory).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        return File(
            root,
            "app/schemas/cn.soul2.imageai.data.db.AppDatabase/$version.json",
        )
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
