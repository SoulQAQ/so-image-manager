package cn.soul2.imageai.ai.credential

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class EncryptedCredential(
    val initializationVector: ByteArray,
    val ciphertext: ByteArray,
)

internal interface CredentialKeyProvider {
    fun getOrCreateEncryptionKey(): SecretKey

    fun getDecryptionKey(): SecretKey?

    fun deleteKey()
}

internal class CredentialCipherException(
    val reason: CredentialUnavailableReason,
    cause: Throwable? = null,
) : Exception(reason.name, cause)

internal class AesGcmCredentialCipher(
    private val keyProvider: CredentialKeyProvider,
) {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): EncryptedCredential = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreateEncryptionKey())
        cipher.updateAAD(associatedData)
        EncryptedCredential(
            initializationVector = cipher.iv.copyOf(),
            ciphertext = cipher.doFinal(plaintext),
        )
    } catch (error: Exception) {
        throw CredentialCipherException(CredentialUnavailableReason.KEY_UNAVAILABLE, error)
    }

    fun decrypt(encrypted: EncryptedCredential, associatedData: ByteArray): ByteArray {
        val key = try {
            keyProvider.getDecryptionKey()
        } catch (error: Exception) {
            throw CredentialCipherException(CredentialUnavailableReason.KEY_UNAVAILABLE, error)
        } ?: throw CredentialCipherException(CredentialUnavailableReason.KEY_UNAVAILABLE)

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, encrypted.initializationVector),
            )
            cipher.updateAAD(associatedData)
            cipher.doFinal(encrypted.ciphertext)
        } catch (error: AEADBadTagException) {
            throw CredentialCipherException(CredentialUnavailableReason.CORRUPT_DATA, error)
        } catch (error: Exception) {
            throw CredentialCipherException(CredentialUnavailableReason.KEY_UNAVAILABLE, error)
        }
    }

    fun deleteKey() {
        try {
            keyProvider.deleteKey()
        } catch (error: Exception) {
            throw CredentialCipherException(CredentialUnavailableReason.KEY_UNAVAILABLE, error)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}

internal class AndroidKeystoreCredentialKeyProvider : CredentialKeyProvider {
    private val keyStore: KeyStore
        get() = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    @Synchronized
    override fun getOrCreateEncryptionKey(): SecretKey = getExistingKey() ?: createKey()

    @Synchronized
    override fun getDecryptionKey(): SecretKey? = getExistingKey()

    @Synchronized
    override fun deleteKey() {
        keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun getExistingKey(): SecretKey? = keyStore.getKey(KEY_ALIAS, null) as? SecretKey

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "soim.ai.credentials.v1"
    }
}
