package cn.soul2.imageai.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.soul2.imageai.analysis.CanonicalAnalysisDraft
import cn.soul2.imageai.analysis.CanonicalMetadataRepository
import cn.soul2.imageai.analysis.CanonicalTermInput
import cn.soul2.imageai.analysis.CorrectionCommand
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.ImagePartition
import cn.soul2.imageai.data.db.entity.ImageSource
import cn.soul2.imageai.data.db.entity.ProviderAuthMode
import cn.soul2.imageai.data.db.entity.ProviderProfileEntity
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SoimBackupServiceTest {
    private lateinit var source: AppDatabase
    private lateinit var target: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    @Test
    fun portableBackupRelinksImagesRestoresCanonicalStateAndNeverExportsCredentials() = runTest {
        source.imageDao().upsert(
            listOf(
                image(1L, "external", 10L, "shared-fingerprint", ImagePartition.PRIVATE),
                image(2L, "external", 20L, "missing-fingerprint", ImagePartition.MAIN),
            ),
        )
        val sourceCanonical = CanonicalMetadataRepository(source)
        sourceCanonical.importAnalysisHistory(draft(OLD_ANALYSIS, 1L, "旧描述"))
        sourceCanonical.activateAnalysis(draft(ACTIVE_ANALYSIS, 1L, "当前描述"))
        sourceCanonical.applyCorrection(1L, CorrectionCommand.SetCaption("用户描述"), 40L)
        source.aiConfigurationDao().upsertProvider(provider("credential-source"))
        val sourceService = SoimBackupService(source, sourceCanonical, nowEpochMillis = { 100L })

        val (json, exported) = sourceService.exportJson()

        assertEquals(2, exported.imageCount)
        assertEquals(2, exported.analysisCount)
        assertFalse(json.contains("credential-source"))
        assertFalse(JSONObject(json).getBoolean("credentialsIncluded"))

        target.imageDao().upsert(
            listOf(image(99L, "external_primary", 999L, "shared-fingerprint", ImagePartition.MAIN)),
        )
        target.aiConfigurationDao().upsertProvider(provider("credential-target"))
        val targetCanonical = CanonicalMetadataRepository(target)
        val restored = SoimBackupService(target, targetCanonical, nowEpochMillis = { 200L })
            .restoreJson(json)

        assertEquals(1, restored.matchedImages)
        assertEquals(1, restored.unmatchedImages)
        assertEquals(2, restored.restoredAnalyses)
        assertEquals(0, restored.conflicts)
        assertNull(target.imageDao().getById(1L))
        assertEquals(ImagePartition.PRIVATE, target.imageDao().getById(99L)?.partition)
        assertEquals(ACTIVE_ANALYSIS, targetCanonical.getEffectiveSnapshot(99L)?.activeAnalysisId)
        assertEquals("用户描述", targetCanonical.getEffectiveSnapshot(99L)?.metadata?.caption)
        assertEquals(2, target.analysisDao().countAnalyses(99L))
        assertFalse(target.imageDao().hasSearchDocument(99L))
        assertEquals("credential-target", target.aiConfigurationDao().getProvider(PROVIDER_ID)?.credentialId)
    }

    @Test
    fun historicalAnalysisNeverReplacesTheActivePointer() = runTest {
        source.imageDao().upsert(listOf(image(1L, "external", 10L, "fingerprint", ImagePartition.MAIN)))
        val sourceCanonical = CanonicalMetadataRepository(source)
        sourceCanonical.activateAnalysis(draft(ACTIVE_ANALYSIS, 1L, "当前描述"))
        sourceCanonical.importAnalysisHistory(draft(OLD_ANALYSIS, 1L, "历史描述"))
        val json = SoimBackupService(source, sourceCanonical).exportJson().first
        target.imageDao().upsert(listOf(image(9L, "external", 10L, "target", ImagePartition.MAIN)))
        val targetCanonical = CanonicalMetadataRepository(target)

        SoimBackupService(target, targetCanonical).restoreJson(json)

        assertEquals(ACTIVE_ANALYSIS, targetCanonical.getEffectiveSnapshot(9L)?.activeAnalysisId)
        assertEquals("当前描述", targetCanonical.getEffectiveSnapshot(9L)?.metadata?.caption)
        assertEquals(2, target.analysisDao().countAnalyses(9L))
    }

    @Test
    fun invalidConfigurationIsRejectedBeforeAnyConfigurationWrite() = runTest {
        source.imageDao().upsert(listOf(image(1L, "external", 10L, "fingerprint", ImagePartition.MAIN)))
        source.aiConfigurationDao().upsertProvider(provider("credential-source"))
        val exported = SoimBackupService(source, CanonicalMetadataRepository(source)).exportJson().first
        val root = JSONObject(exported)
        val provider = root.getJSONArray("providers").getJSONObject(0)
        provider.put("providerId", "provider-invalid")
        provider.put("baseUrl", "not-a-url")
        root.put("providers", JSONArray().put(provider))

        val failure = runCatching {
            SoimBackupService(target, CanonicalMetadataRepository(target)).restoreJson(root.toString())
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertNull(target.aiConfigurationDao().getProvider("provider-invalid"))
    }

    @Test
    fun rejectsUnknownBackupVersionsBeforeWritingAnything() = runTest {
        target.imageDao().upsert(listOf(image(99L, "external", 1L, "fingerprint", ImagePartition.MAIN)))
        val service = SoimBackupService(target, CanonicalMetadataRepository(target))

        val failure = runCatching {
            service.restoreJson("""{"format":"soim-portable-backup","version":99}""")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(1, target.imageDao().getBackupCandidates().size)
        assertEquals(0, target.analysisDao().countAnalyses(99L))
    }

    @Test
    fun preflightUsesRestoreValidationAndDoesNotWriteTargetDatabase() = runTest {
        source.imageDao().upsert(listOf(image(1L, "external", 10L, "fingerprint", ImagePartition.MAIN)))
        source.aiConfigurationDao().upsertProvider(provider("credential-source"))
        val json = SoimBackupService(source, CanonicalMetadataRepository(source)).exportJson().first
        val service = SoimBackupService(target, CanonicalMetadataRepository(target))

        val preflight = service.preflightJson(json)

        assertEquals("soim-portable-backup", preflight.format)
        assertEquals(1, preflight.version)
        assertEquals(1, preflight.imageCount)
        assertEquals(1, preflight.providerCount)
        assertEquals(1, preflight.credentialReentryCount)
        assertFalse(preflight.credentialsIncluded)
        assertTrue(target.imageDao().getBackupCandidates().isEmpty())
        assertNull(target.aiConfigurationDao().getProvider(PROVIDER_ID))
    }

    @Test
    fun preflightAndRestoreBothRejectCredentialBearingBackup() = runTest {
        val json = SoimBackupService(source, CanonicalMetadataRepository(source)).exportJson().first
        val invalid = JSONObject(json).put("credentialsIncluded", true).toString()
        val service = SoimBackupService(target, CanonicalMetadataRepository(target))

        assertTrue(runCatching { service.preflightJson(invalid) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { service.restoreJson(invalid) }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun draft(id: String, imageId: Long, caption: String) = CanonicalAnalysisDraft(
        analysisId = id,
        imageLocalId = imageId,
        schemaVersion = 1,
        caption = caption,
        tags = listOf(CanonicalTermInput("旅行", 0.9)),
        categories = listOf(CanonicalTermInput("照片", 0.8)),
        searchTokens = listOf("海边"),
        extensionJson = null,
        providerProfileId = PROVIDER_ID,
        modelProfileId = "model-main",
        protocolDefinitionId = "openai-responses",
        promptTemplateId = "prompt-v1",
        createdAtEpochMillis = 10L,
        completedAtEpochMillis = 20L,
    )

    private fun provider(credentialId: String) = ProviderProfileEntity(
        providerId = PROVIDER_ID,
        displayName = "供应方",
        baseUrl = "https://example.com/v1",
        authMode = ProviderAuthMode.BEARER,
        authHeaderName = null,
        authPrefix = null,
        credentialId = credentialId,
        headersJson = "{}",
        allowedRedirectOriginsJson = "[]",
        cleartextApproved = false,
        connectTimeoutMillis = 10_000,
        readTimeoutMillis = 60_000,
        writeTimeoutMillis = 60_000,
        maxConcurrency = 1,
        requestsPerMinute = 10,
        requestsPerDay = 100,
        enabled = true,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun image(
        localId: Long,
        volume: String,
        mediaId: Long,
        fingerprint: String,
        partition: ImagePartition,
    ) = ImageEntity(
        localId = localId,
        volumeName = volume,
        mediaStoreId = mediaId,
        contentUri = "content://media/$volume/images/media/$mediaId",
        displayName = "$mediaId.jpg",
        mimeType = "image/jpeg",
        width = 1_920,
        height = 1_080,
        sizeBytes = 1_024L,
        capturedAtEpochMillis = 100L,
        addedAtEpochMillis = 100L,
        modifiedAtEpochMillis = 100L,
        sortTimeEpochMillis = 100L,
        bucketId = 1L,
        bucketName = "相机",
        isFavorite = false,
        quickFingerprint = fingerprint,
        availability = ImageAvailability.AVAILABLE,
        lastSeenSyncRunId = null,
        missingCandidateSinceEpochMillis = null,
        missingObservationCount = 0,
        source = ImageSource.MEDIA_STORE,
        partition = partition,
    )

    private companion object {
        const val PROVIDER_ID = "provider-main"
        const val OLD_ANALYSIS = "123e4567-e89b-12d3-a456-426614174100"
        const val ACTIVE_ANALYSIS = "123e4567-e89b-12d3-a456-426614174101"
    }
}
