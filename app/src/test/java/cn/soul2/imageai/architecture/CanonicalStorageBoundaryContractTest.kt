package cn.soul2.imageai.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalStorageBoundaryContractTest {
    private val root = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
    ) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val mainSource = File(root, "app/src/main/java")

    @Test
    fun appContainerDoesNotExposeTheRoomDatabase() {
        val source = File(mainSource, "cn/soul2/imageai/AppContainer.kt").readText()

        assertTrue(source.contains("private val database: AppDatabase"))
        assertFalse(Regex("(?m)^\\s{4}val database: AppDatabase").containsMatchIn(source))
    }

    @Test
    fun rawCanonicalAndSearchDaosStayInsideTheirStorageOwners() {
        val allowed = setOf(
            "cn/soul2/imageai/analysis/CanonicalMetadataRepository.kt",
            "cn/soul2/imageai/data/db/AppDatabase.kt",
        )
        val offenders = mainSource.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.relativeTo(mainSource).invariantSeparatorsPath !in allowed }
            .filter { file ->
                val source = file.readText()
                source.contains(".analysisDao()") ||
                    source.contains(".effectiveMetadataDao()") ||
                    source.contains(".searchIndexDao()")
            }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()

        assertTrue("Raw canonical DAO access escaped the repository: $offenders", offenders.isEmpty())
    }
}
