package cn.soul2.imageai.architecture

import java.io.File
import java.nio.file.Files
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
        val committed = committedVersion()

        mapOf(
            "patch" to bump(committed.name, "patch"),
            "minor" to bump(committed.name, "minor"),
            "major" to bump(committed.name, "major"),
        ).forEach { (bump, expectedVersion) ->
            val result = runPublisher("-WhatIf", "-Bump", bump)
            assertEquals("Publisher failed for $bump:\n${result.output}", 0, result.exitCode)
            assertTrue(result.output, result.output.contains("SOIM_VERSION_NAME=$expectedVersion"))
            assertTrue(
                result.output,
                result.output.contains("SOIM_VERSION_CODE=${committed.code + 1}"),
            )
            assertEquals("-WhatIf changed publication files", before, publicationSnapshot())
        }
    }

    @Test
    fun publisherDerivesLocalReleaseDateAtRuntime() {
        val text = scriptText()
        val hardcodedReleaseDate = Regex(
            "[$]ReleaseDate\\s*=\\s*\"\\d{4}-\\d{2}-\\d{2}\"",
        )

        assertFalse(hardcodedReleaseDate.containsMatchIn(text))
        assertTrue(text.contains("\$ReleaseDate = Get-Date -Format \"yyyy-MM-dd\""))
    }

    @Test
    fun publisherBuildsEveryGateBeforeCommittingMetadata() {
        val text = scriptText()
        val main = text.substringAfter("$" + "CommittedProperties = Get-CommittedVersionProperties")

        assertTrue(text.contains("[ValidateSet(\"patch\", \"minor\", \"major\")]"))
        assertTrue(text.contains("[CmdletBinding(SupportsShouldProcess = \$true)]"))
        assertInOrder(
            main,
            "Invoke-GradleGate",
            "Get-ConnectedDeviceState",
            "Invoke-ConnectedTests",
            "Test-ApkMetadata",
            "Test-DebugSignature",
            "Test-ReleaseUnsigned",
            "Test-ApkSecurity",
            "Test-LatestRoomSchema",
            "Get-ApkSha256",
            "ShouldProcess",
            "Enter-PublisherLock",
            "Test-SnapshotUnchanged",
            "Commit-PublicationMetadata",
            "Exit-PublisherLock",
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
    fun publisherUsesHeadBaselineAndLocksThePromptToCommitWindow() {
        val text = scriptText()
        val mainStart = text.indexOf("$" + "CommittedProperties = Get-CommittedVersionProperties")
        assertTrue("Missing committed publication baseline", mainStart >= 0)
        val main = text.substring(mainStart)
        val commit = text.substringAfter("function Commit-PublicationMetadata")
            .substringBefore("$" + "CommittedProperties = Get-CommittedVersionProperties")
        val lock = text.substringAfter("function Enter-PublisherLock")
            .substringBefore("function Exit-PublisherLock")

        assertInOrder(
            main,
            "Get-CommittedVersionProperties",
            "SOIM_VERSION_NAME=",
            "if (\$WhatIfPreference)",
            "Assert-PublicationTargetsClean",
        )
        assertInOrder(
            main,
            "Get-PublicationSnapshot",
            "Assert-PublicationTargetsClean",
            "Get-CurrentMarker",
            "LegacyChangelogBytes",
        )
        assertInOrder(
            main,
            "ShouldProcess",
            "Enter-PublisherLock",
            "Test-SnapshotUnchanged",
            "Commit-PublicationMetadata",
            "Exit-PublisherLock",
        )
        assertTrue(lock.contains("[IO.FileShare]::None"))
        assertTrue(commit.contains("[hashtable] \$OriginalSnapshot"))
        assertFalse(commit.contains("Get-PublicationSnapshot"))
        assertTrue(commit.contains("\$OriginalSnapshot[\$ChangelogFile].BytesBase64"))
        assertTrue(commit.contains("Legacy changelog bytes do not match the original snapshot"))
        assertTrue(main.contains("\$LegacyChangelogBytes = [Convert]::FromBase64String("))
        assertTrue(main.contains("\$preBuildSnapshot[\$ChangelogFile].BytesBase64"))
        assertFalse(main.contains("[IO.File]::ReadAllBytes(\$ChangelogFile)"))
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
    fun releaseAndDeviceGatesRejectAmbiguousOrUnsupportedStates() {
        val text = scriptText()
        val release = text.substringAfter("function Test-ReleaseUnsigned")
            .substringBefore("function Test-ApkSecurity")
        val parser = text.substringAfter("function Parse-AdbDeviceRows")
            .substringBefore("function Get-ConnectedDeviceState")
        val devices = text.substringAfter("function Get-ConnectedDeviceState")
            .substringBefore("function Invoke-ConnectedTests")
        val connected = text.substringAfter("function Invoke-ConnectedTests")
            .substringBefore("function Write-AtomicBytes")

        assertTrue(release.contains("Test-ApkMetadata"))
        assertTrue(release.contains("DOES NOT VERIFY"))
        assertTrue(release.contains("Missing META-INF/MANIFEST.MF"))
        assertTrue(release.contains("Unsigned release diagnostic was not recognized"))

        assertTrue(devices.contains("Android SDK adb.exe is missing"))
        assertTrue(devices.contains("@(\"devices\", \"-l\")"))
        assertTrue(devices.contains("ro.build.version.sdk"))
        assertTrue(devices.contains("SKIPPED_NO_DEVICE"))
        assertTrue(devices.contains("SKIPPED_NO_USABLE_DEVICE"))
        assertTrue(devices.contains("SKIPPED_NO_API29_DEVICE"))
        assertTrue(devices.contains("ApiLevel"))
        assertTrue(devices.contains("Serial"))
        assertTrue(devices.contains("Parse-AdbDeviceRows"))
        assertTrue(parser.contains("device|offline|unauthorized"))
        assertFalse(parser.contains("Invoke-CapturedNative"))

        assertTrue(connected.contains("\$env:ANDROID_SERIAL = \$Serial"))
        assertTrue(connected.contains("Test-Path Env:ANDROID_SERIAL"))
        assertTrue(connected.contains("Remove-Item Env:ANDROID_SERIAL"))
    }

    @Test
    fun connectedDeviceParserIgnoresAdbDaemonStartupDiagnostics() {
        val result = runDeviceHarness(
            """
            __PS__fakeAdb = Join-Path __PS__HarnessRoot "adb.exe"
            [IO.File]::WriteAllText(__PS__fakeAdb, "")
            function Invoke-CapturedNative {
                param([string] __PS__FilePath, [string[]] __PS__ArgumentList)
                return [pscustomobject] @{
                    ExitCode = 0
                    Output = "* daemon not running; starting now at tcp:5037`r`n* daemon started successfully`r`nList of devices attached`r`n`r`n"
                }
            }
            __PS__state = Get-ConnectedDeviceState -Adb __PS__fakeAdb
            if (__PS__state.Status -cne "SKIPPED_NO_DEVICE") {
                throw ("daemon diagnostics were treated as devices: " + __PS__state.Status)
            }
            Write-Output "ADB_DAEMON_DIAGNOSTICS=PASS"
            """.trimIndent(),
        )
        assertEquals(result.output, 0, result.exitCode)
        assertTrue(result.output, result.output.contains("ADB_DAEMON_DIAGNOSTICS=PASS"))
    }

    @Test
    fun latestRoomSchemaIsPinnedToVersionSevenBatchAnalysisFoundation() {
        val schema = scriptText().substringAfter("function Test-LatestRoomSchema")
            .substringBefore("function Get-ApkSha256")

        assertTrue(schema.contains("\$latest.BaseName -cne \"7\""))
        assertTrue(schema.contains("[int] \$schema.database.version -ne 7"))
        assertTrue(schema.contains("app_setting"))
        assertTrue(schema.contains("media_sync_checkpoint"))
        assertTrue(schema.contains("media_sync_run"))
        assertTrue(schema.contains("image_analysis"))
        assertTrue(schema.contains("effective_image_term"))
        assertTrue(schema.contains("provider_profile"))
        assertTrue(schema.contains("batch_analysis_run"))
        assertTrue(schema.contains("batch_analysis_item"))
        assertTrue(schema.contains("model_profile"))
        assertTrue(schema.contains("protocol_definition"))
        assertTrue(schema.contains("search_document_fts"))
        assertTrue(schema.contains("source"))
        assertTrue(schema.contains("fts5|image_fts"))
    }

    @Test
    fun publisherUsesAtomicBomFreeMetadataAndFailureRollback() {
        val text = scriptText()

        listOf(
            "UTF8Encoding(\$false)",
            "WriteAllBytes",
            "GetRandomFileName",
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

    @Test
    fun publicationSnapshotKeepsZeroByteMarkersAsStableBase64() {
        val text = scriptText()
        val snapshot = text.substringAfter("function Get-PublicationSnapshot")
            .substringBefore("function Restore-PublicationMetadata")
        val restore = text.substringAfter("function Restore-PublicationMetadata")
            .substringBefore("function Test-SnapshotUnchanged")
        val compare = text.substringAfter("function Test-SnapshotUnchanged")
            .substringBefore("function New-ChangelogEntry")

        assertTrue(snapshot.contains("BytesBase64"))
        assertTrue(snapshot.contains("[Convert]::ToBase64String"))
        assertTrue(snapshot.contains("else { \"\" }"))
        assertFalse(snapshot.contains("Bytes = if"))
        assertTrue(restore.contains("[Convert]::FromBase64String(\$state.BytesBase64)"))
        assertTrue(compare.contains("[Convert]::ToBase64String(\$current)"))
        assertTrue(compare.contains("-cne \$state.BytesBase64"))
    }

    @Test
    fun atomicWritesUseSiblingTempsAndRollbackSkipsUnchangedFiles() {
        val text = scriptText()
        val atomicWrite = text.substringAfter("function Write-AtomicBytes")
            .substringBefore("function Get-PublicationSnapshot")
        val restore = text.substringAfter("function Restore-PublicationMetadata")
            .substringBefore("function Test-SnapshotUnchanged")

        assertTrue(atomicWrite.contains("[IO.Path]::GetRandomFileName()"))
        assertTrue(atomicWrite.contains("Join-Path \$directory"))
        assertFalse(atomicWrite.contains("GetTempFileName"))
        assertTrue(atomicWrite.contains("\$backup = Join-Path \$directory"))
        assertTrue(atomicWrite.contains("[IO.File]::Replace(\$temporary, \$Path, \$backup)"))
        assertFalse(atomicWrite.contains("[IO.File]::Replace(\$temporary, \$Path, \$null)"))
        assertTrue(atomicWrite.contains("[IO.File]::Exists(\$backup)"))
        assertTrue(atomicWrite.contains("Remove-Item -LiteralPath \$backup -Force"))
        assertTrue(restore.contains("\$currentExists = [IO.File]::Exists(\$path)"))
        assertTrue(restore.contains("\$currentBase64"))
        assertTrue(restore.contains("\$state.BytesBase64"))
        assertTrue(restore.contains("continue"))
        assertTrue(restore.contains("\$rollbackFailures"))
        assertTrue(restore.contains("Publication rollback failed for"))
        assertTrue(atomicWrite.contains("\$cleanupFailures"))
        assertTrue(atomicWrite.contains("Write-Warning"))
        assertTrue(text.contains("function Report-AtomicArtifacts"))
        assertFalse(text.contains("function Assert-NoAtomicArtifacts"))

        val commit = text.substringAfter("function Commit-PublicationMetadata")
            .substringBefore("$" + "CommittedProperties = Get-CommittedVersionProperties")
        assertFalse(commit.contains("Write-Error"))
        assertFalse(commit.contains("Assert-NoAtomicArtifacts"))
        assertTrue(commit.contains("Report-AtomicArtifacts"))
        assertTrue(commit.contains("Primary failure:"))
        assertTrue(commit.contains("Rollback failure:"))
    }

    @Test
    fun atomicHarnessRestoresMultiplePartiallyChangedTargets() {
        val result = runAtomicHarness(
            """
            __PS__a = Join-Path __PS__CaseRoot "a.txt"
            __PS__b = Join-Path __PS__CaseRoot "b.txt"
            __PS__c = Join-Path __PS__CaseRoot "c.txt"
            [IO.File]::WriteAllText(__PS__a, "old-a")
            [IO.File]::WriteAllText(__PS__b, "old-b")
            __PS__snapshot = Get-PublicationSnapshot -Paths @(__PS__a, __PS__b, __PS__c)
            [IO.File]::WriteAllText(__PS__a, "new-a")
            [IO.File]::Delete(__PS__b)
            [IO.File]::WriteAllText(__PS__c, "new-c")
            Restore-PublicationMetadata -Snapshot __PS__snapshot
            if ([IO.File]::ReadAllText(__PS__a) -cne "old-a") { throw "a was not restored" }
            if ([IO.File]::ReadAllText(__PS__b) -cne "old-b") { throw "b was not restored" }
            if ([IO.File]::Exists(__PS__c)) { throw "created target was not removed" }
            Write-Output "ATOMIC_MULTI_RESTORE=PASS"
            """.trimIndent(),
        )
        assertEquals(result.output, 0, result.exitCode)
        assertTrue(result.output, result.output.contains("ATOMIC_MULTI_RESTORE=PASS"))
    }

    @Test
    fun atomicHarnessContinuesAfterOneRestoreFails() {
        val result = runAtomicHarness(
            """
            __PS__paths = @(1..3 | ForEach-Object { Join-Path __PS__CaseRoot ("target-" + __PS___ + ".txt") })
            foreach (__PS__path in __PS__paths) { [IO.File]::WriteAllText(__PS__path, "old") }
            __PS__snapshot = Get-PublicationSnapshot -Paths __PS__paths
            foreach (__PS__path in __PS__paths) { [IO.File]::WriteAllText(__PS__path, "new") }
            __PS__script:OriginalWriteAtomic = __PS__{function:Write-AtomicBytes}
            __PS__script:RestoreCalls = 0
            function Write-AtomicBytes {
                param(
                    [Parameter(Mandatory = __PS__true)][string] __PS__Path,
                    [Parameter(Mandatory = __PS__true)][AllowEmptyCollection()][byte[]] __PS__Bytes
                )
                __PS__script:RestoreCalls++
                if (__PS__script:RestoreCalls -eq 1) { throw "intentional restore failure" }
                & __PS__script:OriginalWriteAtomic -Path __PS__Path -Bytes __PS__Bytes
            }
            __PS__failure = __PS__null
            try { Restore-PublicationMetadata -Snapshot __PS__snapshot } catch { __PS__failure = __PS___ }
            if (__PS__null -eq __PS__failure) { throw "aggregate rollback failure was not reported" }
            if (__PS__failure.Exception.Message -notmatch "intentional restore failure") { throw "primary restore error missing" }
            if (__PS__script:RestoreCalls -ne 3) { throw "restore stopped after the first failure" }
            __PS__oldCount = @(__PS__paths | Where-Object { [IO.File]::ReadAllText(__PS___) -ceq "old" }).Count
            if (__PS__oldCount -ne 2) { throw "remaining targets were not restored" }
            Write-Output "ATOMIC_CONTINUE_RESTORE=PASS"
            """.trimIndent(),
        )
        assertEquals(result.output, 0, result.exitCode)
        assertTrue(result.output, result.output.contains("ATOMIC_CONTINUE_RESTORE=PASS"))
    }

    @Test
    fun atomicHarnessCleanupFailureDoesNotBlockDataOrRollback() {
        val result = runAtomicHarness(
            """
            __PS__target = Join-Path __PS__CaseRoot "target.txt"
            [IO.File]::WriteAllText(__PS__target, "old")
            __PS__snapshot = Get-PublicationSnapshot -Paths @(__PS__target)
            function Remove-Item {
                [CmdletBinding()]
                param([Parameter(Mandatory = __PS__true)][string] __PS__LiteralPath, [switch] __PS__Force)
                if ([IO.Path]::GetFileName(__PS__LiteralPath).StartsWith(".soim-publish-")) {
                    throw "intentional cleanup failure"
                }
                Microsoft.PowerShell.Management\Remove-Item -LiteralPath __PS__LiteralPath -Force:__PS__Force
            }
            Write-AtomicBytes -Path __PS__target -Bytes ([Text.Encoding]::UTF8.GetBytes("new"))
            if ([IO.File]::ReadAllText(__PS__target) -cne "new") { throw "replacement was masked by cleanup" }
            Restore-PublicationMetadata -Snapshot __PS__snapshot
            if ([IO.File]::ReadAllText(__PS__target) -cne "old") { throw "rollback was blocked by cleanup" }
            Write-Output "ATOMIC_CLEANUP_ROLLBACK=PASS"
            """.trimIndent(),
        )
        assertEquals(result.output, 0, result.exitCode)
        assertTrue(result.output, result.output.contains("ATOMIC_CLEANUP_ROLLBACK=PASS"))
    }

    private fun runAtomicHarness(body: String): ProcessResult {
        val directory = Files.createTempDirectory("soim-publish-atomic-").toFile()
        return try {
            val caseRoot = File(directory, "case").apply { mkdirs() }
            val harness = File(directory, "harness.ps1")
            val source = """
                Set-StrictMode -Version Latest
                __PS__ErrorActionPreference = "Stop"
                __PS__source = [IO.File]::ReadAllText('__SCRIPT__', [Text.Encoding]::UTF8)
                __PS__tokens = __PS__null
                __PS__parseErrors = __PS__null
                __PS__ast = [Management.Automation.Language.Parser]::ParseInput(
                    __PS__source,
                    [ref] __PS__tokens,
                    [ref] __PS__parseErrors
                )
                if (__PS__parseErrors.Count -ne 0) { throw (__PS__parseErrors -join "`n") }
                __PS__functionNames = @(
                    "Write-AtomicBytes",
                    "Get-PublicationSnapshot",
                    "Restore-PublicationMetadata"
                )
                foreach (__PS__functionName in __PS__functionNames) {
                    __PS__definition = __PS__ast.Find(
                        {
                            param(__PS__node)
                            __PS__node -is [Management.Automation.Language.FunctionDefinitionAst] -and
                                __PS__node.Name -ceq __PS__functionName
                        },
                        __PS__true
                    )
                    if (__PS__null -eq __PS__definition) { throw "Missing function: __PS__functionName" }
                    Invoke-Expression __PS__definition.Extent.Text
                }
                __PS__CaseRoot = '__CASE_ROOT__'
                $body
            """.trimIndent()
                .replace("__PS__", "$")
                .replace("__SCRIPT__", psSingleQuoted(script.absolutePath))
                .replace("__CASE_ROOT__", psSingleQuoted(caseRoot.absolutePath))
            harness.writeText(source, Charsets.UTF_8)
            runPowerShellFile(harness)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun runDeviceHarness(body: String): ProcessResult {
        val directory = Files.createTempDirectory("soim-publish-device-").toFile()
        return try {
            val harness = File(directory, "harness.ps1")
            val source = """
                Set-StrictMode -Version Latest
                __PS__ErrorActionPreference = "Stop"
                __PS__source = [IO.File]::ReadAllText('__SCRIPT__', [Text.Encoding]::UTF8)
                __PS__tokens = __PS__null
                __PS__parseErrors = __PS__null
                __PS__ast = [Management.Automation.Language.Parser]::ParseInput(
                    __PS__source,
                    [ref] __PS__tokens,
                    [ref] __PS__parseErrors
                )
                if (__PS__parseErrors.Count -ne 0) { throw (__PS__parseErrors -join "`n") }
                __PS__functionNames = @("Parse-AdbDeviceRows", "Get-ConnectedDeviceState")
                foreach (__PS__functionName in __PS__functionNames) {
                    __PS__definition = __PS__ast.Find(
                        {
                            param(__PS__node)
                            __PS__node -is [Management.Automation.Language.FunctionDefinitionAst] -and
                                __PS__node.Name -ceq __PS__functionName
                        },
                        __PS__true
                    )
                    if (__PS__null -eq __PS__definition) { throw "Missing function: __PS__functionName" }
                    Invoke-Expression __PS__definition.Extent.Text
                }
                __PS__HarnessRoot = '__HARNESS_ROOT__'
                $body
            """.trimIndent()
                .replace("__PS__", "$")
                .replace("__SCRIPT__", psSingleQuoted(script.absolutePath))
                .replace("__HARNESS_ROOT__", psSingleQuoted(directory.absolutePath))
            harness.writeText(source, Charsets.UTF_8)
            runPowerShellFile(harness)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun committedVersion(): CommittedVersion {
        val result = runProcess(listOf("git", "show", "HEAD:version.properties"))
        check(result.exitCode == 0) { result.output }
        val values = result.output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val separator = line.indexOf('=')
                check(separator > 0) { "Invalid committed version property: $line" }
                line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()
        val name = requireNotNull(values["SOIM_VERSION_NAME"])
        check(STRICT_SEMVER.matches(name)) { "Committed version is not strict SemVer: $name" }
        val code = requireNotNull(values["SOIM_VERSION_CODE"]).toInt()
        check(code > 0) { "Committed version code must be positive" }
        return CommittedVersion(name, code)
    }

    private fun bump(version: String, kind: String): String {
        val match = requireNotNull(STRICT_SEMVER.matchEntire(version))
        var major = match.groupValues[1].toLong()
        var minor = match.groupValues[2].toLong()
        var patch = match.groupValues[3].toLong()
        when (kind) {
            "patch" -> patch++
            "minor" -> {
                minor++
                patch = 0
            }
            "major" -> {
                major++
                minor = 0
                patch = 0
            }
            else -> error("Unsupported bump: $kind")
        }
        return "$major.$minor.$patch"
    }

    private fun psSingleQuoted(value: String): String = value.replace("'", "''")

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
        return runProcess(command)
    }

    private fun runPowerShellFile(file: File): ProcessResult = runProcess(
        listOf(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            file.absolutePath,
        ),
    )

    private fun runProcess(command: List<String>): ProcessResult {
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

    private data class CommittedVersion(
        val name: String,
        val code: Int,
    )

    private companion object {
        val STRICT_SEMVER = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
    }
}
