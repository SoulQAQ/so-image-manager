package cn.soul2.imageai.ai.credential

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidKeystoreCredentialStoreTest {
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var keyProvider: InMemoryCredentialKeyProvider
    private lateinit var store: AndroidKeystoreCredentialStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = context.getSharedPreferences(
            AndroidKeystoreCredentialStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        preferences.edit().clear().commit()
        keyProvider = InMemoryCredentialKeyProvider()
        store = AndroidKeystoreCredentialStore(
            preferences,
            AesGcmCredentialCipher(keyProvider),
        )
    }

    @Test
    fun storesOnlyCiphertextAndRoundTripsUnicodeSecret() {
        val original = "sk-test-密钥".toCharArray()
        store.put(FIRST_ID, original)

        val persisted = preferences.getString(preferenceKey(FIRST_ID), null).orEmpty()
        assertTrue(persisted.startsWith("v1:"))
        assertFalse(persisted.contains("sk-test"))
        val result = store.read(FIRST_ID) as CredentialReadResult.Available
        assertArrayEquals(original, result.secret)
        result.secret.fill('\u0000')
    }

    @Test
    fun associatedDataPreventsCiphertextFromBeingMovedToAnotherCredential() {
        store.put(FIRST_ID, "first-secret".toCharArray())
        store.put(SECOND_ID, "second-secret".toCharArray())
        val firstPayload = preferences.getString(preferenceKey(FIRST_ID), null)
        preferences.edit().putString(preferenceKey(SECOND_ID), firstPayload).commit()

        assertEquals(
            CredentialReadResult.Unavailable(CredentialUnavailableReason.CORRUPT_DATA),
            store.read(SECOND_ID),
        )
    }

    @Test
    fun malformedPayloadAndMissingKeystoreKeyHaveDistinctResults() {
        preferences.edit().putString(preferenceKey(FIRST_ID), "not-a-payload").commit()
        assertEquals(
            CredentialReadResult.Unavailable(CredentialUnavailableReason.CORRUPT_DATA),
            store.read(FIRST_ID),
        )

        store.put(FIRST_ID, "secret".toCharArray())
        keyProvider.deleteKey()
        assertEquals(
            CredentialReadResult.Unavailable(CredentialUnavailableReason.KEY_UNAVAILABLE),
            store.read(FIRST_ID),
        )
    }

    @Test
    fun deleteAndExplicitResetRemovePersistedCredentials() {
        store.put(FIRST_ID, "first".toCharArray())
        store.put(SECOND_ID, "second".toCharArray())

        assertTrue(store.delete(FIRST_ID))
        assertFalse(store.delete(FIRST_ID))
        assertEquals(CredentialReadResult.Missing, store.read(FIRST_ID))

        store.resetAll()
        assertEquals(CredentialReadResult.Missing, store.read(SECOND_ID))
        assertFalse(keyProvider.hasKey)
    }

    @Test
    fun rejectsBlankOversizedOrUnsafeIdentifiersAndSecrets() {
        assertThrows(IllegalArgumentException::class.java) {
            store.put("unsafe/id", "secret".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.put(FIRST_ID, charArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.put(FIRST_ID, CharArray(16_385) { 'a' })
        }
    }

    private fun preferenceKey(credentialId: String) =
        "${AndroidKeystoreCredentialStore.PREFERENCE_KEY_PREFIX}$credentialId"

    private class InMemoryCredentialKeyProvider : CredentialKeyProvider {
        private var key: SecretKey? = null

        val hasKey: Boolean
            get() = key != null

        override fun getOrCreateEncryptionKey(): SecretKey = key ?: KeyGenerator
            .getInstance("AES")
            .apply { init(256) }
            .generateKey()
            .also { key = it }

        override fun getDecryptionKey(): SecretKey? = key

        override fun deleteKey() {
            key = null
        }
    }

    private companion object {
        const val FIRST_ID = "credential-first"
        const val SECOND_ID = "credential-second"
    }
}
