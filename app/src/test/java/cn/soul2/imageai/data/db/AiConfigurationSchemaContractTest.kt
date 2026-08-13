package cn.soul2.imageai.data.db

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConfigurationSchemaContractTest {
    @Test
    fun versionTenRetainsProviderModelProtocolAndRuntimeConfiguration() {
        val schema = projectFile(
            "app/schemas/cn.soul2.imageai.data.db.AppDatabase/10.json",
        )
        assertTrue("Room schema v10 must be exported", schema.isFile)
        val text = schema.readText()

        assertTrue(Regex("\\\"version\\\"\\s*:\\s*10").containsMatchIn(text))
        listOf(
            "provider_profile",
            "provider_route",
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
        listOf("daily_image_limit", "only_show_analyzed", "automatic_failover_enabled").forEach { column ->
            assertTrue("Missing AI runtime column: $column", text.contains("\"columnName\": \"$column\""))
        }
        listOf(
            "daily_token_limit", "wifi_only", "charging_only", "battery_not_low",
            "execution_start_minute", "execution_end_minute", "retry_limit",
            "circuit_breaker_threshold", "circuit_breaker_cooldown_minutes",
        ).forEach { column ->
            assertTrue("Missing scheduler runtime column: $column", text.contains("\"columnName\": \"$column\""))
        }
    }

    @Test
    fun everyDatabaseFactoryRegistersMigrationSevenToEightWithoutDestructiveFallback() {
        val factory = projectFile(
            "app/src/main/java/cn/soul2/imageai/data/db/AppDatabaseFactory.kt",
        ).readText()

        assertTrue(factory.contains("MIGRATION_4_5"))
        assertTrue(factory.contains("MIGRATION_5_6"))
        assertTrue(factory.contains("MIGRATION_6_7"))
        assertTrue(factory.contains("MIGRATION_7_8"))
        assertTrue(factory.contains("MIGRATION_8_9"))
        assertTrue(factory.contains("MIGRATION_9_10"))
        assertFalse(factory.contains("fallbackToDestructiveMigration"))
    }

    private fun projectFile(path: String): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(workingDirectory).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        return File(root, path)
    }
}
