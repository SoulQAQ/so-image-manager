package cn.soul2.imageai.data.db

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConfigurationSchemaContractTest {
    @Test
    fun versionSevenRetainsProviderModelProtocolAndRuntimeConfiguration() {
        val schema = projectFile(
            "app/schemas/cn.soul2.imageai.data.db.AppDatabase/7.json",
        )
        assertTrue("Room schema v7 must be exported", schema.isFile)
        val text = schema.readText()

        assertTrue(Regex("\\\"version\\\"\\s*:\\s*7").containsMatchIn(text))
        listOf(
            "provider_profile",
            "model_profile",
            "protocol_definition",
            "ai_runtime_setting",
        ).forEach { table ->
            assertTrue("Missing AI configuration table: $table", text.contains("\"tableName\": \"$table\""))
        }
        listOf(
            "index_model_profile_provider_id",
            "index_model_profile_protocol_definition_id",
            "index_ai_runtime_setting_default_model_profile_id",
        ).forEach { index -> assertTrue("Missing index: $index", text.contains(index)) }
        assertFalse("Credentials must not be stored in Room", text.contains("api_key", ignoreCase = true))
        assertFalse("Credential ciphertext must not be stored in Room", text.contains("ciphertext", true))
    }

    @Test
    fun everyDatabaseFactoryRegistersMigrationSixToSevenWithoutDestructiveFallback() {
        val factory = projectFile(
            "app/src/main/java/cn/soul2/imageai/data/db/AppDatabaseFactory.kt",
        ).readText()

        assertTrue(factory.contains("MIGRATION_4_5"))
        assertTrue(factory.contains("MIGRATION_5_6"))
        assertTrue(factory.contains("MIGRATION_6_7"))
        assertFalse(factory.contains("fallbackToDestructiveMigration"))
    }

    private fun projectFile(path: String): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(workingDirectory).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        return File(root, path)
    }
}
