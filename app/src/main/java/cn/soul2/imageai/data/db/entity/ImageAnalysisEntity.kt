package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "image_analysis",
    foreignKeys = [
        ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["local_id"],
            childColumns = ["image_local_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["image_local_id", "completed_at_epoch_millis"]),
        Index(value = ["analysis_id", "image_local_id"], unique = true),
    ],
)
data class ImageAnalysisEntity(
    @PrimaryKey
    @ColumnInfo(name = "analysis_id")
    val analysisId: String,
    @ColumnInfo(name = "image_local_id")
    val imageLocalId: Long,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    val caption: String,
    @ColumnInfo(name = "extension_json")
    val extensionJson: String?,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "provider_profile_id")
    val providerProfileId: String,
    @ColumnInfo(name = "model_profile_id")
    val modelProfileId: String,
    @ColumnInfo(name = "protocol_definition_id")
    val protocolDefinitionId: String,
    @ColumnInfo(name = "prompt_template_id")
    val promptTemplateId: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long,
)
