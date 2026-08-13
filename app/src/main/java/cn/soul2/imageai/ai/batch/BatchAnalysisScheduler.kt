package cn.soul2.imageai.ai.batch

import androidx.work.ExistingWorkPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import cn.soul2.imageai.data.db.entity.AiRuntimeSettingEntity
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class BatchAnalysisScheduler(
    private val workManager: WorkManager,
    private val runtimeSettings: suspend () -> AiRuntimeSettingEntity?,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun enqueue(notBeforeEpochMillis: Long? = null) {
        val runtime = runtimeSettings()
        val now = nowEpochMillis()
        val allowedAt = maxOf(notBeforeEpochMillis ?: now, runtime?.nextExecutionAt(now) ?: now)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (runtime?.wifiOnly == true) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(runtime?.chargingOnly == true)
            .setRequiresBatteryNotLow(runtime?.batteryNotLow != false)
            .build()
        val request = OneTimeWorkRequestBuilder<BatchAnalysisWorker>()
            .setConstraints(constraints)
            .setInitialDelay((allowedAt - now).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "soim_batch_ai_analysis"
    }
}

private fun AiRuntimeSettingEntity.nextExecutionAt(nowEpochMillis: Long): Long {
    val zone = ZoneId.systemDefault()
    val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zone)
    val minute = now.hour * 60 + now.minute
    if (isWithinExecutionWindow(minute)) return nowEpochMillis
    val todayStart = now.toLocalDate().atStartOfDay(zone)
    val startToday = todayStart.plusMinutes(executionStartMinute.toLong())
    val next = if (startToday.isAfter(now)) startToday else startToday.plusDays(1)
    return next.toInstant().toEpochMilli()
}
