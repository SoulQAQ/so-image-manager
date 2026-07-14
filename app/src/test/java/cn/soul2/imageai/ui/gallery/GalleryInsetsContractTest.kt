package cn.soul2.imageai.ui.gallery

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryInsetsContractTest {
    @Test
    fun galleryTopBarLeavesSystemInsetsToTheRootScaffold() {
        val source = File(
            "src/main/java/cn/soul2/imageai/ui/gallery/GalleryScreen.kt",
        ).readText()

        assertTrue(
            "The root Scaffold already applies system-bar padding",
            source.contains("windowInsets = WindowInsets(0, 0, 0, 0)"),
        )
    }
}
