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
        assertTrue(build.contains("val hasReleaseSigning"))
        assertTrue(build.contains("listOf("))
        assertTrue(build.contains("if (hasReleaseSigning)"))
        assertTrue(build.contains("signingConfigs.getByName(\"release\")"))
        assertTrue(build.contains("SOIM_SIGNING_STORE_FILE"))
        assertTrue(build.contains("SOIM_SIGNING_STORE_PASSWORD"))
        assertTrue(build.contains("SOIM_SIGNING_KEY_ALIAS"))
        assertTrue(build.contains("SOIM_SIGNING_KEY_PASSWORD"))
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"true\""))
        assertTrue(network.readText().contains("cleartextTrafficPermitted=\"true\""))
    }
}
