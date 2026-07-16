package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_runtime_setting",
    foreignKeys = [
        ForeignKey(
            entity = ModelProfileEntity::class,
            parentColumns = ["model_profile_id"],
            childColumns = ["default_model_profile_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("default_model_profile_id")],
)
data class AiRuntimeSettingEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = SINGLETON_ID,
    @ColumnInfo(name = "default_model_profile_id")
    val defaultModelProfileId: String?,
    @ColumnInfo(name = "global_max_concurrency")
    val globalMaxConcurrency: Int,
    @ColumnInfo(name = "global_requests_per_minute")
    val globalRequestsPerMinute: Int,
    @ColumnInfo(name = "global_requests_per_day")
    val globalRequestsPerDay: Int,
    @ColumnInfo(name = "prompt_text")
    val promptText: String,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
