package cn.soul2.imageai.ai.credential

sealed interface CredentialReadResult {
    data class Available(val secret: CharArray) : CredentialReadResult
    data object Missing : CredentialReadResult
    data class Unavailable(val reason: CredentialUnavailableReason) : CredentialReadResult
}

enum class CredentialUnavailableReason {
    KEY_UNAVAILABLE,
    CORRUPT_DATA,
    STORAGE_FAILURE,
}

interface AiCredentialStore {
    /** The caller owns the returned array and should clear it immediately after use. */
    fun read(credentialId: String): CredentialReadResult

    fun put(credentialId: String, secret: CharArray)

    fun delete(credentialId: String): Boolean

    fun resetAll()
}

class CredentialStoreException(
    val reason: CredentialUnavailableReason,
) : IllegalStateException("Credential storage is unavailable: $reason")
