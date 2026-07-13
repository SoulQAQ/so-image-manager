package cn.soul2.imageai.data.db

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSchemaContractTest {
    @Test
    fun versionOneContainsOnlyTheNewBaselineTable() {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(workingDirectory).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val schema = File(
            root,
            "app/schemas/cn.soul2.imageai.data.db.AppDatabase/1.json",
        )
        assertTrue("Room schema must be exported", schema.isFile)
        val text = schema.readText()
        assertTrue(text.contains("app_setting"))
        listOf("image_fts", "fts5", "image_ai", "image_feature", "image_query_cache")
            .forEach { assertFalse("Legacy schema term remains: $it", text.contains(it)) }
    }
}
