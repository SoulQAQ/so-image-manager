package cn.soul2.imageai.architecture

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishContractTest {
    private val root = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
    ) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val script = File(root, "scripts/publish-test-apk.ps1")

    @Test
    fun whatIfAppliesSemanticBumpsAndWritesNothing() {
        assertTrue("Missing publisher: ${script.path}", script.isFile)
        val before = publicationSnapshot()

        mapOf(
            "patch" to "0.3.3",
            "minor" to "0.4.0",
            "major" to "1.0.0",
        ).forEach { (bump, expectedVersion) ->
            val result = runPublisher("-WhatIf", "-Bump", bump)
            assertEquals("Publisher failed for $bump:\n${result.output}", 0, result.exitCode)
            assertTrue(result.output, result.output.contains("SOIM_VERSION_NAME=$expectedVersion"))
            assertTrue(result.output, result.output.contains("SOIM_VERSION_CODE=6"))
            assertEquals("-WhatIf changed publication files", before, publicationSnapshot())
        }
    }

    @Test
    fun publisherBuildsEveryGateBeforeCommittingMetadata() {
        val text = scriptText()

        assertTrue(text.contains("[ValidateSet(\"patch\", \"minor\", \"major\")]"))
        assertTrue(text.contains("[CmdletBinding(SupportsShouldProcess = \$true)]"))
        assertInOrder(
            text,
            "Invoke-GradleGate",
            "Test-ApkMetadata",
            "Test-DebugSignature",
            "Test-ReleaseUnsigned",
            "Get-FileHash",
            "Commit-PublicationMetadata",
        )

        listOf(
            "clean",
            ":app:testDebugUnitTest",
            ":app:lintDebug",
            ":app:compileDebugAndroidTestKotlin",
            ":app:assembleDebug",
            ":app:assembleRelease",
        ).forEach { task -> assertTrue("Missing Gradle gate: $task", text.contains(task)) }
        assertTrue(text.contains("connectedDebugAndroidTest"))
        assertTrue(text.contains("adb"))
        assertTrue(text.contains("-PsoimVersionName="))
        assertTrue(text.contains("-PsoimVersionCode="))
    }

    @Test
    fun publisherVerifiesApkIdentitySignatureAndDigest() {
        val text = scriptText()

        listOf(
            "aapt.exe",
            "dump",
            "badging",
            "cn.soul2.imageai",
            "minSdkVersion:'29'",
            "targetSdkVersion:'36'",
            "versionName='",
            "versionCode='",
            "apksigner.bat",
            "verify",
            "--print-certs",
            "Android Debug",
            "SHA256",
            "soim-v\$CandidateVersion-debug.apk",
        ).forEach { token -> assertTrue("Missing APK verification token: $token", text.contains(token)) }
        assertTrue(text.contains("@(\"AI_API_KEY\", \"apikey.txt\", \"aiApiKey\")"))

        val sdkReader = text.substringAfter("function Get-SdkDirectory")
            .substringBefore("function Get-AndroidTools")
        assertTrue(sdkReader.contains("Select-String -LiteralPath \$localProperties"))
        assertTrue(sdkReader.contains("-Pattern \"^\\s*sdk\\.dir\\s*=\""))
        listOf("Get-Content", "ReadAllText", "ReadAllLines", "ConvertFrom-StringData")
            .forEach { parser ->
                assertFalse("local.properties reader must not parse other values", sdkReader.contains(parser))
            }

        val sourceBytes = script.readBytes()
        val hasUtf8Bom = sourceBytes.size >= 3 &&
            sourceBytes[0] == 0xEF.toByte() &&
            sourceBytes[1] == 0xBB.toByte() &&
            sourceBytes[2] == 0xBF.toByte()
        assertTrue(
            "PowerShell 5.1 source must be ASCII or carry a UTF-8 BOM",
            hasUtf8Bom || sourceBytes.all { it.toInt() and 0x80 == 0 },
        )
    }

    @Test
    fun publisherUsesAtomicBomFreeMetadataAndFailureRollback() {
        val text = scriptText()

        listOf(
            "UTF8Encoding(\$false)",
            "WriteAllBytes",
            "GetTempFileName",
            "Move-Item",
            "Restore-PublicationMetadata",
            "current_version_is_",
            "GetFiles(\"current_version_is_*\")",
            "LegacyChangelogBytes",
        ).forEach { token -> assertTrue("Missing atomic publication token: $token", text.contains(token)) }
        assertInOrder(
            text,
            "Test-ApkMetadata",
            "Test-DebugSignature",
            "Get-FileHash",
            "Commit-PublicationMetadata",
        )
        assertTrue(text.contains("catch"))
        assertTrue(text.contains("Restore-PublicationMetadata"))
    }

    private fun scriptText(): String {
        assertTrue("Missing publisher: ${script.path}", script.isFile)
        return script.readText(Charsets.UTF_8)
    }

    private fun runPublisher(vararg arguments: String): ProcessResult {
        val command = mutableListOf(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            script.absolutePath,
        )
        command += arguments
        val process = ProcessBuilder(command)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return ProcessResult(process.waitFor(), output)
    }

    private fun publicationSnapshot(): Map<String, String> {
        val apkDirectory = File(root, "apk")
        val paths = buildList {
            add(File(root, "version.properties"))
            add(File(apkDirectory, "ver_change_log.md"))
            addAll(
                apkDirectory.listFiles()
                    .orEmpty()
                    .filter {
                        it.name.startsWith("current_version_is_") ||
                            (it.name.startsWith("soim-v") && it.name.endsWith("-debug.apk"))
                    },
            )
        }
        return paths.sortedBy { it.canonicalPath }.associate { file ->
            val key = file.relativeTo(root).invariantSeparatorsPath
            key to if (file.isFile) sha256(file.readBytes()) else "missing"
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun assertInOrder(text: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val current = text.indexOf(token, previous + 1)
            assertTrue("Missing or out-of-order token: $token", current > previous)
            previous = current
        }
    }

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )
}
