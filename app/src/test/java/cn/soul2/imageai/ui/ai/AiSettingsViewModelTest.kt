package cn.soul2.imageai.ui.ai

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.soul2.imageai.ai.config.AiConfigurationRepository
import cn.soul2.imageai.ai.credential.AiCredentialStore
import cn.soul2.imageai.ai.credential.CredentialReadResult
import cn.soul2.imageai.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AiSettingsViewModelTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: AiConfigurationRepository
    private lateinit var credentialStore: InMemoryCredentialStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryExecutor { command -> command.run() }
            .setTransactionExecutor { command -> command.run() }
            .build()
        repository = AiConfigurationRepository(database)
        credentialStore = InMemoryCredentialStore()
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun savesValidatedActiveConfigurationAndEncryptedCredentialReference() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = AiSettingsViewModel(
            repository,
            credentialStore,
            nowEpochMillis = { 100L },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.loading)
        viewModel.updateForm(
            viewModel.uiState.value.form.copy(
                modelId = "gpt-vision-test",
                apiKey = "sk-secret-value",
                globalConcurrency = "3",
                providerConcurrency = "2",
                modelConcurrency = "1",
            ),
        )

        viewModel.save()
        advanceUntilIdle()
        val saved = viewModel.uiState.value

        assertNull(saved.error)
        assertEquals("", saved.form.apiKey)
        assertEquals(
            AiSettingsViewModel.MODEL_ID,
            repository.getRuntimeSetting()?.defaultModelProfileId,
        )
        assertEquals("gpt-vision-test", repository.getModel(AiSettingsViewModel.MODEL_ID)?.modelId)
        assertEquals(3, repository.getRuntimeSetting()?.globalMaxConcurrency)
        assertEquals(2, repository.getProvider(AiSettingsViewModel.PROVIDER_ID)?.maxConcurrency)
        assertEquals(1, repository.getModel(AiSettingsViewModel.MODEL_ID)?.maxConcurrency)
        assertEquals("sk-secret-value", credentialStore.values[AiSettingsViewModel.CREDENTIAL_ID])
        assertFalse(
            database.openHelper.readableDatabase.query(
                "SELECT 1 FROM provider_profile WHERE base_url LIKE '%sk-secret%'",
            ).use { it.moveToFirst() },
        )
    }

    @Test
    fun invalidFieldsAndMissingCredentialDoNotPartiallyPersistConfiguration() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = AiSettingsViewModel(
            repository,
            credentialStore,
            nowEpochMillis = { 100L },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.loading)

        viewModel.updateForm(viewModel.uiState.value.form.copy(modelId = "", apiKey = "secret"))
        viewModel.save()
        advanceUntilIdle()
        val invalid = viewModel.uiState.value
        assertEquals(AiSettingsError.INVALID_FIELDS, invalid.error)
        assertNull(repository.getProvider(AiSettingsViewModel.PROVIDER_ID))
        assertFalse(credentialStore.values.containsKey(AiSettingsViewModel.CREDENTIAL_ID))

        viewModel.updateForm(viewModel.uiState.value.form.copy(modelId = "gpt-test", apiKey = ""))
        viewModel.save()
        advanceUntilIdle()
        val missing = viewModel.uiState.value
        assertEquals(AiSettingsError.CREDENTIAL_REQUIRED, missing.error)
        assertNull(repository.getProvider(AiSettingsViewModel.PROVIDER_ID))
    }

    private class InMemoryCredentialStore : AiCredentialStore {
        val values = mutableMapOf<String, String>()

        override fun read(credentialId: String): CredentialReadResult =
            values[credentialId]?.let { CredentialReadResult.Available(it.toCharArray()) }
                ?: CredentialReadResult.Missing

        override fun put(credentialId: String, secret: CharArray) {
            values[credentialId] = secret.concatToString()
        }

        override fun delete(credentialId: String): Boolean = values.remove(credentialId) != null

        override fun resetAll() {
            values.clear()
        }
    }
}
