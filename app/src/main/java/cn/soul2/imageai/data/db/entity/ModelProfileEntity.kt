package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ModelProtocolType {
    OPENAI_RESPONSES,
    OPENAI_CHAT_COMPLETIONS,
    ANTHROPIC_MESSAGES,
    GEMINI_GENERATE_CONTENT,
    CUSTOM_JSON,
}

@Entity(
    tableName = "model_profile",
    foreignKeys = [
        ForeignKey(
            entity = ProviderProfileEntity::class,
            parentColumns = ["provider_id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProtocolDefinitionEntity::class,
            parentColumns = ["protocol_definition_id"],
            childColumns = ["protocol_definition_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("provider_id"), Index("protocol_definition_id")],
)
data class ModelProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "model_profile_id")
    val modelProfileId: String,
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "model_id")
    val modelId: String,
    @ColumnInfo(name = "protocol_type")
    val protocolType: ModelProtocolType,
    @ColumnInfo(name = "protocol_definition_id")
    val protocolDefinitionId: String?,
    @ColumnInfo(name = "supports_vision")
    val supportsVision: Boolean,
    @ColumnInfo(name = "max_output_tokens")
    val maxOutputTokens: Int?,
    val temperature: Double?,
    @ColumnInfo(name = "max_image_edge")
    val maxImageEdge: Int,
    @ColumnInfo(name = "max_image_bytes")
    val maxImageBytes: Int,
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
