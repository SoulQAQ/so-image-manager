package cn.soul2.imageai.architecture

import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ProductContractTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) {
        it.parentFile
    }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun visibleProductCopyUsesTheChineseBaseline() {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File(root, "app/src/main/res/values/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        val strings = (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }

        assertEquals("SoIM", strings["app_name"])
        assertEquals("首页", strings["nav_home"])
        assertEquals("图库", strings["nav_library"])
        assertEquals("任务", strings["nav_tasks"])
        assertEquals("设置", strings["nav_settings"])
        assertEquals("暂无已索引图片", strings["home_empty"])
        assertEquals("图库暂无图片", strings["library_empty"])
        assertEquals("暂无进行中的任务", strings["tasks_empty"])
        assertEquals("暂无设置项", strings["settings_empty"])

        listOf(
            "No indexed images",
            "No library items",
            "No active tasks",
            "No settings configured",
        ).forEach { englishCopy ->
            assertFalse("English empty-state copy remains: $englishCopy", englishCopy in strings.values)
        }
    }

    @Test
    fun versionSourceIsCentralizedAndPublishOverridesAreExplicit() {
        val versionFile = File(root, "version.properties")
        assertTrue("version.properties must be the default version source", versionFile.isFile)

        val defaults = Properties().apply {
            versionFile.inputStream().use(::load)
        }
        val versionName = defaults.getProperty("SOIM_VERSION_NAME")
        assertTrue(
            "SOIM_VERSION_NAME must be strict SemVer: $versionName",
            STRICT_SEMVER.matches(versionName.orEmpty()),
        )
        val versionCode = defaults.getProperty("SOIM_VERSION_CODE").toIntOrNull()
        assertTrue("SOIM_VERSION_CODE must be positive", versionCode != null && versionCode > 0)

        val buildFile = File(root, "app/build.gradle.kts").readText()
        assertTrue(buildFile.contains("version.properties"))
        assertTrue(buildFile.contains("gradle.startParameter.projectProperties"))
        assertTrue(buildFile.contains("[\"soimVersionName\"]"))
        assertTrue(buildFile.contains("[\"soimVersionCode\"]"))
        assertFalse(buildFile.contains("providers.gradleProperty"))
        assertTrue(buildFile.contains("SOIM_VERSION_NAME"))
        assertTrue(buildFile.contains("SOIM_VERSION_CODE"))
        assertFalse(Regex("""\bversionName\s*=\s*\"[^\"]*\"""").containsMatchIn(buildFile))
        assertFalse(Regex("""\bversionCode\s*=\s*\d+""").containsMatchIn(buildFile))
    }

    private companion object {
        val STRICT_SEMVER = Regex(
            """(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)""",
        )
    }
}
