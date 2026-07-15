package cn.soul2.imageai.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GallerySyncLifecycleContractTest {
    private val root = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
    ) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun mainActivityUsesOneLifecycleAwareAccessSyncPath() {
        val source = File(
            root,
            "app/src/main/java/cn/soul2/imageai/MainActivity.kt",
        ).readText()

        assertEquals(
            2,
            Regex("\\.collectAsStateWithLifecycle\\(\\)").findAll(source).count(),
        )
        assertFalse(source.contains(".collectAsState()"))
        assertTrue(source.contains("gallerySyncAccessCoordinator"))
        assertTrue(source.contains("onExplicitSelectionChanged = reconcileExplicitSelection"))
        assertTrue(source.contains("observePassiveAccess(access)"))
        assertTrue(source.contains("if (!permissionRequestInFlight)"))
        assertFalse(source.contains("container.mediaSyncScheduler.requestInitial()"))
    }
}
