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
    @ColumnInfo(name = "daily_image_limit")
    val dailyImageLimit: Int = 0,
    @ColumnInfo(name = "daily_token_limit")
    val dailyTokenLimit: Long = 0L,
    @ColumnInfo(name = "wifi_only")
    val wifiOnly: Boolean = false,
    @ColumnInfo(name = "charging_only")
    val chargingOnly: Boolean = false,
    @ColumnInfo(name = "battery_not_low")
    val batteryNotLow: Boolean = true,
    @ColumnInfo(name = "execution_start_minute")
    val executionStartMinute: Int = 0,
    @ColumnInfo(name = "execution_end_minute")
    val executionEndMinute: Int = 0,
    @ColumnInfo(name = "retry_limit")
    val retryLimit: Int = 4,
    @ColumnInfo(name = "circuit_breaker_threshold")
    val circuitBreakerThreshold: Int = 5,
    @ColumnInfo(name = "circuit_breaker_cooldown_minutes")
    val circuitBreakerCooldownMinutes: Int = 30,
    @ColumnInfo(name = "only_show_analyzed")
    val onlyShowAnalyzed: Boolean = false,
    @ColumnInfo(name = "automatic_failover_enabled")
    val automaticFailoverEnabled: Boolean = true,
    @ColumnInfo(name = "prompt_text")
    val promptText: String,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
) {
    fun isWithinExecutionWindow(minuteOfDay: Int): Boolean {
        if (executionStartMinute == executionEndMinute) return true
        return if (executionStartMinute < executionEndMinute) {
            minuteOfDay in executionStartMinute until executionEndMinute
        } else {
            minuteOfDay >= executionStartMinute || minuteOfDay < executionEndMinute
        }
    }

    companion object {
        const val SINGLETON_ID = 1
    }
}
