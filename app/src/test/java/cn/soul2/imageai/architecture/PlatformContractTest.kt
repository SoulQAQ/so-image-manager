package cn.soul2.imageai.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformContractTest {
    @Test
    fun androidAndSchemaExportBaselineIsPinned() {
        val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val buildFile = File(root, "app/build.gradle.kts").readText()
        assertTrue(buildFile.contains("compileSdk = 36"))
        assertTrue(buildFile.contains("minSdk = 29"))
        assertTrue(buildFile.contains("targetSdk = 36"))
        assertTrue(buildFile.contains("room.schemaLocation"))
    }
}
