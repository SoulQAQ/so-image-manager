package cn.soul2.imageai.ai.config

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.data.db.entity.AiRuntimeSettingEntity
import cn.soul2.imageai.data.db.entity.ModelProfileEntity
import cn.soul2.imageai.data.db.entity.ModelProtocolType
import cn.soul2.imageai.data.db.entity.ProtocolDefinitionEntity
import cn.soul2.imageai.data.db.entity.ProviderAuthMode
import cn.soul2.imageai.data.db.entity.ProviderProfileEntity
import cn.soul2.imageai.ai.protocol.CustomJsonProtocolDefinitionTest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AiConfigurationRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: AiConfigurationRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = AiConfigurationRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun openAiResponsesModelPersistsWithoutCustomDefinition() = runTest {
        repository.saveProvider(provider())
        repository.saveModel(model(protocolType = ModelProtocolType.OPENAI_RESPONSES))
        repository.saveRuntimeSetting(runtimeSetting())

        assertEquals("gpt-image-reader", repository.getModel(MODEL_ID)?.modelId)
        assertNull(repository.getModel(MODEL_ID)?.protocolDefinitionId)
        assertEquals(MODEL_ID, repository.getRuntimeSetting()?.defaultModelProfileId)
    }

    @Test
    fun customJsonRequiresAnExistingDefinitionAndResponsesRejectsOne() = runTest {
        repository.saveProvider(provider())

        assertValidationFails {
            repository.saveModel(
                model(
                    protocolType = ModelProtocolType.CUSTOM_JSON,
                    protocolDefinitionId = PROTOCOL_ID,
                ),
            )
        }
        repository.saveProtocol(protocol())
        repository.saveModel(
            model(
                protocolType = ModelProtocolType.CUSTOM_JSON,
                protocolDefinitionId = PROTOCOL_ID,
            ),
        )
        assertEquals(PROTOCOL_ID, repository.getModel(MODEL_ID)?.protocolDefinitionId)

        assertValidationFails {
            repository.saveModel(
                model(
                    protocolType = ModelProtocolType.OPENAI_RESPONSES,
                    protocolDefinitionId = PROTOCOL_ID,
                ),
            )
        }
    }

    @Test
    fun cleartextAndAuthenticationMustBeExplicitlyConfigured() = runTest {
        assertValidationFails {
            repository.saveProvider(
                provider(baseUrl = "http://192.168.1.8:8000/v1", cleartextApproved = false),
            )
        }
        repository.saveProvider(
            provider(baseUrl = "http://192.168.1.8:8000/v1", cleartextApproved = true),
        )
        assertEquals(
            "http://192.168.1.8:8000/v1",
            repository.getProvider(PROVIDER_ID)?.baseUrl,
        )

        assertValidationFails {
            repository.saveProvider(provider(credentialId = null))
        }
        assertValidationFails {
            repository.saveProvider(
                provider(
                    authMode = ProviderAuthMode.NONE,
                    credentialId = CREDENTIAL_ID,
                ),
            )
        }
    }

    @Test
    fun quotasTimeoutsImageLimitsAndDefaultReferencesAreBounded() = runTest {
        assertValidationFails {
            repository.saveProvider(provider(maxConcurrency = 0))
        }
        assertValidationFails {
            repository.saveProvider(provider(requestsPerMinute = -1))
        }
        assertValidationFails {
            repository.saveProvider(provider(connectTimeoutMillis = 99))
        }

        repository.saveProvider(provider())
        assertValidationFails {
            repository.saveModel(model(maxImageBytes = AiConfigurationLimits.MAX_IMAGE_BYTES + 1))
        }
        assertValidationFails {
            repository.saveRuntimeSetting(runtimeSetting(defaultModelProfileId = "missing-model"))
        }
    }

    @Test
    fun malformedProtocolHeadersAndRedirectOriginsNeverReachRoom() = runTest {
        assertValidationFails {
            repository.saveProtocol(protocol().copy(definitionJson = "{}"))
        }
        assertValidationFails {
            repository.saveProvider(provider().copy(headersJson = "{\"X-Test\":42}"))
        }
        assertValidationFails {
            repository.saveProvider(
                provider().copy(allowedRedirectOriginsJson = "[\"https://example.com/path\"]"),
            )
        }
    }

    private suspend fun assertValidationFails(block: suspend () -> Unit) {
        assertThrows(AiConfigurationValidationException::class.java) {
            kotlinx.coroutines.runBlocking { block() }
        }
    }

    private fun provider(
        baseUrl: String = "https://api.example.com/v1",
        cleartextApproved: Boolean = false,
        authMode: ProviderAuthMode = ProviderAuthMode.BEARER,
        credentialId: String? = CREDENTIAL_ID,
        maxConcurrency: Int = 2,
        requestsPerMinute: Int = 30,
        connectTimeoutMillis: Int = 10_000,
    ) = ProviderProfileEntity(
        providerId = PROVIDER_ID,
        displayName = "测试供应方",
        baseUrl = baseUrl,
        authMode = authMode,
        authHeaderName = null,
        authPrefix = null,
        credentialId = credentialId,
        headersJson = "{}",
        allowedRedirectOriginsJson = "[]",
        cleartextApproved = cleartextApproved,
        connectTimeoutMillis = connectTimeoutMillis,
        readTimeoutMillis = 60_000,
        writeTimeoutMillis = 60_000,
        maxConcurrency = maxConcurrency,
        requestsPerMinute = requestsPerMinute,
        requestsPerDay = 1_000,
        enabled = true,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun protocol() = ProtocolDefinitionEntity(
        protocolDefinitionId = PROTOCOL_ID,
        displayName = "自定义协议",
        definitionJson = CustomJsonProtocolDefinitionTest.DEFINITION,
        enabled = true,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun model(
        protocolType: ModelProtocolType = ModelProtocolType.OPENAI_RESPONSES,
        protocolDefinitionId: String? = null,
        maxImageBytes: Int = 1_500_000,
    ) = ModelProfileEntity(
        modelProfileId = MODEL_ID,
        providerId = PROVIDER_ID,
        displayName = "视觉模型",
        modelId = "gpt-image-reader",
        protocolType = protocolType,
        protocolDefinitionId = protocolDefinitionId,
        supportsVision = true,
        maxOutputTokens = 2_048,
        temperature = 0.2,
        maxImageEdge = 1_600,
        maxImageBytes = maxImageBytes,
        maxConcurrency = 2,
        requestsPerMinute = 20,
        requestsPerDay = 500,
        enabled = true,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun runtimeSetting(defaultModelProfileId: String? = MODEL_ID) = AiRuntimeSettingEntity(
        defaultModelProfileId = defaultModelProfileId,
        globalMaxConcurrency = 3,
        globalRequestsPerMinute = 40,
        globalRequestsPerDay = 1_500,
        promptText = "请分析图片",
        updatedAtEpochMillis = 1L,
    )

    private companion object {
        const val PROVIDER_ID = "provider-openai"
        const val MODEL_ID = "model-responses"
        const val PROTOCOL_ID = "protocol-custom"
        const val CREDENTIAL_ID = "credential-provider-openai"
    }
}
