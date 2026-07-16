package cn.soul2.imageai.ai.credential

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class AndroidKeystoreCredentialStore internal constructor(
    private val preferences: SharedPreferences,
    private val cipher: AesGcmCredentialCipher,
) : AiCredentialStore {
    constructor(context: Context) : this(
        preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ),
        cipher = AesGcmCredentialCipher(AndroidKeystoreCredentialKeyProvider()),
    )

    @Synchronized
    override fun read(credentialId: String): CredentialReadResult {
        validateCredentialId(credentialId)
        val payload = try {
            preferences.getString(preferenceKey(credentialId), null)
        } catch (_: RuntimeException) {
            return CredentialReadResult.Unavailable(CredentialUnavailableReason.STORAGE_FAILURE)
        } ?: return CredentialReadResult.Missing

        val encrypted = parsePayload(payload)
            ?: return CredentialReadResult.Unavailable(CredentialUnavailableReason.CORRUPT_DATA)
        val plaintext = try {
            cipher.decrypt(encrypted, associatedData(credentialId))
        } catch (error: CredentialCipherException) {
            return CredentialReadResult.Unavailable(error.reason)
        }
        return try {
            CredentialReadResult.Available(decodeUtf8(plaintext))
        } catch (_: Exception) {
            CredentialReadResult.Unavailable(CredentialUnavailableReason.CORRUPT_DATA)
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    override fun put(credentialId: String, secret: CharArray) {
        validateCredentialId(credentialId)
        if (secret.isEmpty() || secret.size > MAX_SECRET_CHARACTERS) {
            throw IllegalArgumentException("Credential is blank or too long")
        }
        val plaintext = encodeUtf8(secret)
        try {
            if (plaintext.size > MAX_SECRET_BYTES) {
                throw IllegalArgumentException("Credential exceeds the byte limit")
            }
            val encrypted = try {
                cipher.encrypt(plaintext, associatedData(credentialId))
            } catch (error: CredentialCipherException) {
                throw CredentialStoreException(error.reason)
            }
            if (!preferences.edit().putString(preferenceKey(credentialId), encodePayload(encrypted)).commit()) {
                throw CredentialStoreException(CredentialUnavailableReason.STORAGE_FAILURE)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    override fun delete(credentialId: String): Boolean {
        validateCredentialId(credentialId)
        val key = preferenceKey(credentialId)
        if (!preferences.contains(key)) return false
        if (!preferences.edit().remove(key).commit()) {
            throw CredentialStoreException(CredentialUnavailableReason.STORAGE_FAILURE)
        }
        return true
    }

    @Synchronized
    override fun resetAll() {
        if (!preferences.edit().clear().commit()) {
            throw CredentialStoreException(CredentialUnavailableReason.STORAGE_FAILURE)
        }
        try {
            cipher.deleteKey()
        } catch (error: CredentialCipherException) {
            throw CredentialStoreException(error.reason)
        }
    }

    private fun parsePayload(payload: String): EncryptedCredential? {
        val pieces = payload.split(PAYLOAD_SEPARATOR, limit = 3)
        if (pieces.size != 3 || pieces[0] != PAYLOAD_VERSION) return null
        return try {
            val initializationVector = Base64.decode(pieces[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(pieces[2], Base64.NO_WRAP)
            if (initializationVector.size !in MIN_IV_BYTES..MAX_IV_BYTES || ciphertext.isEmpty()) {
                null
            } else {
                EncryptedCredential(initializationVector, ciphertext)
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun encodePayload(encrypted: EncryptedCredential): String = buildString {
        append(PAYLOAD_VERSION)
        append(PAYLOAD_SEPARATOR)
        append(Base64.encodeToString(encrypted.initializationVector, Base64.NO_WRAP))
        append(PAYLOAD_SEPARATOR)
        append(Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP))
    }

    private fun associatedData(credentialId: String): ByteArray =
        "$PAYLOAD_VERSION:$credentialId".toByteArray(StandardCharsets.UTF_8)

    private fun preferenceKey(credentialId: String) = "$PREFERENCE_KEY_PREFIX$credentialId"

    private fun validateCredentialId(value: String) {
        if (
            value.isBlank() ||
            value.length > MAX_CREDENTIAL_ID_CHARACTERS ||
            !value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        ) {
            throw IllegalArgumentException("Invalid credentialId")
        }
    }

    private fun encodeUtf8(value: CharArray): ByteArray {
        val byteBuffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        return ByteArray(byteBuffer.remaining()).also(byteBuffer::get)
    }

    private fun decodeUtf8(value: ByteArray): CharArray {
        val charBuffer = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value))
        return CharArray(charBuffer.remaining()).also(charBuffer::get)
    }

    internal companion object {
        const val PREFERENCES_NAME = "soim_ai_credentials"
        const val PREFERENCE_KEY_PREFIX = "credential."
        private const val PAYLOAD_VERSION = "v1"
        private const val PAYLOAD_SEPARATOR = ':'
        private const val MAX_CREDENTIAL_ID_CHARACTERS = 128
        private const val MAX_SECRET_CHARACTERS = 16_384
        private const val MAX_SECRET_BYTES = 32_768
        private const val MIN_IV_BYTES = 12
        private const val MAX_IV_BYTES = 32
    }
}
