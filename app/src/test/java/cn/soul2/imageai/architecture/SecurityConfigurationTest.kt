package cn.soul2.imageai.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityConfigurationTest {
    private val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun manifestAndReleasePolicyMatchFrozenBaseline() {
        val build = File(root, "app/build.gradle.kts").readText()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val network = File(root, "app/src/main/res/xml/network_security_config.xml")
        assertFalse(build.contains("create(\"release\")"))
        assertFalse(build.contains("signingConfigs.getByName(\"release\")"))
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"true\""))
        assertTrue(network.readText().contains("cleartextTrafficPermitted=\"true\""))
    }
}
