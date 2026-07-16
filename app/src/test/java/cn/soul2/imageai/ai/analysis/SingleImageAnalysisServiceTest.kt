package cn.soul2.imageai.ai.analysis

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.soul2.imageai.ai.image.ImagePreparationException
import cn.soul2.imageai.ai.image.ImagePreparationFailure
import cn.soul2.imageai.ai.image.ImagePreprocessor
import cn.soul2.imageai.ai.image.PreparedImage
import cn.soul2.imageai.ai.output.CanonicalAiPayload
import cn.soul2.imageai.analysis.CanonicalMetadataRepository
import cn.soul2.imageai.analysis.CanonicalTermInput
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.data.db.entity.AiRuntimeSettingEntity
import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.ModelProfileEntity
import cn.soul2.imageai.data.db.entity.ModelProtocolType
import cn.soul2.imageai.data.db.entity.ProviderAuthMode
import cn.soul2.imageai.data.db.entity.ProviderProfileEntity
import cn.soul2.imageai.search.RoomSearchProjectionWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SingleImageAnalysisServiceTest {
    private lateinit var database: AppDatabase
    private lateinit var canonicalRepository: CanonicalMetadataRepository

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.imageDao().upsert(listOf(image()))
        canonicalRepository = CanonicalMetadataRepository(
            database,
            RoomSearchProjectionWriter(database.searchIndexDao()),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun successfulCallPersistsCanonicalMetadataAndMakesTermsImmediatelySearchable() = runTest {
        val preprocessor = RecordingImagePreprocessor()
        var now = 10L
        val service = service(
            preprocessor = preprocessor,
            client = AiModelClient {
                CanonicalAiPayload(
                    caption = "雨夜中的城市街道",
                    tags = listOf(CanonicalTermInput("霓虹灯", 0.94)),
                    categories = listOf(CanonicalTermInput("城市")),
                    searchTokens = listOf("雨夜", "Shanghai"),
                )
            },
            now = { now.also { now += 10L } },
        )

        val result = service.analyze(ImageAnalysisTarget(IMAGE_ID, CONTENT_URI))

        assertEquals(SingleImageAnalysisResult.Success(ANALYSIS_ID, 1L), result)
        val metadata = requireNotNull(canonicalRepository.observeEffectiveMetadata(IMAGE_ID).first())
        assertEquals("雨夜中的城市街道", metadata.caption)
        assertEquals(
            setOf("霓虹灯", "城市", "雨夜", "Shanghai"),
            metadata.terms.map { it.displayValue }.toSet(),
        )
        assertEquals(
            SingleImageAnalysisService.BUILTIN_OPENAI_RESPONSES_PROTOCOL_ID,
            metadata.activeAnalysis?.protocolDefinitionId,
        )
        assertTrue(metadata.activeAnalysis?.promptTemplateId.orEmpty().startsWith("prompt.sha256."))
        assertFalse(metadata.activeAnalysis?.promptTemplateId.orEmpty().contains("分析图片"))
        assertEquals(
            listOf(IMAGE_ID),
            database.searchIndexDao().findExactMappings("霓虹灯", 10).map { it.imageLocalId },
        )
        assertTrue(preprocessor.returnedBytes.all { it == 0.toByte() })
    }

    @Test
    fun missingConfigurationStopsBeforeImageReadOrModelCall() = runTest {
        var imageReads = 0
        var modelCalls = 0
        val service = SingleImageAnalysisService(
            configurationResolver = AiAnalysisConfigurationResolver {
                throw AiConfigurationResolutionException(AiConfigurationFailure.RUNTIME_MISSING)
            },
            imagePreprocessor = ImagePreprocessor { _, _, _ ->
                imageReads += 1
                PreparedImage(byteArrayOf(1), "image/jpeg", 1, 1)
            },
            clients = AiModelClientRegistry(
                mapOf(ModelProtocolType.OPENAI_RESPONSES to AiModelClient {
                    modelCalls += 1
                    payload()
                }),
            ),
            canonicalRepository = canonicalRepository,
        )

        val result = service.analyze(ImageAnalysisTarget(IMAGE_ID, CONTENT_URI))

        assertEquals(
            SingleImageAnalysisResult.Failure(
                SingleImageAnalysisFailure.CONFIGURATION_REQUIRED,
                AiConfigurationFailure.RUNTIME_MISSING.name,
            ),
            result,
        )
        assertEquals(0, imageReads)
        assertEquals(0, modelCalls)
    }

    @Test
    fun modelAndImageFailuresMapToRecoverableUserFacingReasons() = runTest {
        val limited = service(
            preprocessor = RecordingImagePreprocessor(),
            client = AiModelClient { throw AiModelException(AiModelFailure.QUOTA_REJECTED) },
        ).analyze(ImageAnalysisTarget(IMAGE_ID, CONTENT_URI))
        assertEquals(
            SingleImageAnalysisResult.Failure(
                SingleImageAnalysisFailure.REQUEST_LIMITED,
                AiModelFailure.QUOTA_REJECTED.name,
            ),
            limited,
        )

        val unavailable = service(
            preprocessor = ImagePreprocessor { _, _, _ ->
                throw ImagePreparationException(ImagePreparationFailure.SOURCE_UNAVAILABLE)
            },
            client = AiModelClient { payload() },
        ).analyze(ImageAnalysisTarget(IMAGE_ID, CONTENT_URI))
        assertEquals(
            SingleImageAnalysisResult.Failure(
                SingleImageAnalysisFailure.IMAGE_PREPARATION_FAILED,
                ImagePreparationFailure.SOURCE_UNAVAILABLE.name,
            ),
            unavailable,
        )
    }

    private fun service(
        preprocessor: ImagePreprocessor,
        client: AiModelClient,
        now: () -> Long = { 10L },
    ) = SingleImageAnalysisService(
        configurationResolver = AiAnalysisConfigurationResolver { configuration() },
        imagePreprocessor = preprocessor,
        clients = AiModelClientRegistry(mapOf(ModelProtocolType.OPENAI_RESPONSES to client)),
        canonicalRepository = canonicalRepository,
        nowEpochMillis = now,
        newAnalysisId = { ANALYSIS_ID },
    )

    private class RecordingImagePreprocessor : ImagePreprocessor {
        val returnedBytes = byteArrayOf(1, 2, 3, 4)

        override suspend fun prepare(
            contentUri: String,
            maxEdge: Int,
            maxBytes: Int,
        ) = PreparedImage(returnedBytes, "image/jpeg", 2, 2)
    }

    private fun configuration() = ResolvedAiAnalysisConfiguration(
        runtime = AiRuntimeSettingEntity(
            defaultModelProfileId = MODEL_ID,
            globalMaxConcurrency = 2,
            globalRequestsPerMinute = 30,
            globalRequestsPerDay = 1_000,
            promptText = "请用中文分析图片",
            updatedAtEpochMillis = 1L,
        ),
        provider = ProviderProfileEntity(
            providerId = PROVIDER_ID,
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthMode.BEARER,
            authHeaderName = null,
            authPrefix = null,
            credentialId = "credential-openai",
            headersJson = "{}",
            allowedRedirectOriginsJson = "[]",
            cleartextApproved = false,
            connectTimeoutMillis = 10_000,
            readTimeoutMillis = 60_000,
            writeTimeoutMillis = 60_000,
            maxConcurrency = 2,
            requestsPerMinute = 20,
            requestsPerDay = 500,
            enabled = true,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        ),
        model = ModelProfileEntity(
            modelProfileId = MODEL_ID,
            providerId = PROVIDER_ID,
            displayName = "视觉模型",
            modelId = "gpt-test",
            protocolType = ModelProtocolType.OPENAI_RESPONSES,
            protocolDefinitionId = null,
            supportsVision = true,
            maxOutputTokens = 2_048,
            temperature = 0.2,
            maxImageEdge = 1_600,
            maxImageBytes = 1_500_000,
            maxConcurrency = 1,
            requestsPerMinute = 10,
            requestsPerDay = 200,
            enabled = true,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        ),
        protocolDefinition = null,
    )

    private fun payload() = CanonicalAiPayload(
        caption = "测试图片",
        tags = emptyList(),
        categories = emptyList(),
        searchTokens = emptyList(),
    )

    private fun image() = ImageEntity(
        localId = IMAGE_ID,
        volumeName = "external",
        mediaStoreId = 42L,
        contentUri = CONTENT_URI,
        displayName = "42.jpg",
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
        quickFingerprint = "fingerprint",
        availability = ImageAvailability.AVAILABLE,
        lastSeenSyncRunId = null,
        missingCandidateSinceEpochMillis = null,
        missingObservationCount = 0,
    )

    private companion object {
        const val IMAGE_ID = 42L
        const val CONTENT_URI = "content://media/external/images/media/42"
        const val PROVIDER_ID = "provider-openai"
        const val MODEL_ID = "model-openai"
        const val ANALYSIS_ID = "123e4567-e89b-12d3-a456-426614174000"
    }
}
