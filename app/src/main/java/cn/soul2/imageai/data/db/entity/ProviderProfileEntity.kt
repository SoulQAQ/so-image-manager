package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ProviderAuthMode {
    NONE,
    BEARER,
    API_KEY_HEADER,
}

@Entity(tableName = "provider_profile")
data class ProviderProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "base_url")
    val baseUrl: String,
    @ColumnInfo(name = "auth_mode")
    val authMode: ProviderAuthMode,
    @ColumnInfo(name = "auth_header_name")
    val authHeaderName: String?,
    @ColumnInfo(name = "auth_prefix")
    val authPrefix: String?,
    @ColumnInfo(name = "credential_id")
    val credentialId: String?,
    @ColumnInfo(name = "headers_json")
    val headersJson: String,
    @ColumnInfo(name = "allowed_redirect_origins_json")
    val allowedRedirectOriginsJson: String,
    @ColumnInfo(name = "cleartext_approved")
    val cleartextApproved: Boolean,
    @ColumnInfo(name = "connect_timeout_millis")
    val connectTimeoutMillis: Int,
    @ColumnInfo(name = "read_timeout_millis")
    val readTimeoutMillis: Int,
    @ColumnInfo(name = "write_timeout_millis")
    val writeTimeoutMillis: Int,
    @ColumnInfo(name = "max_concurrency")
    val maxConcurrency: Int,
    @ColumnInfo(name = "requests_per_minute")
    val requestsPerMinute: Int,
    @ColumnInfo(name = "requests_per_day")
    val requestsPerDay: Int,
    val enabled: Boolean,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)
