package cn.soul2.imageai.ai.quota

import android.content.Context
import android.content.SharedPreferences

internal data class PersistentQuotaUsage(
    val scopeKey: String,
    val minuteWindowStartEpochMillis: Long,
    val minuteCount: Int,
    val utcDayNumber: Long,
    val dayCount: Int,
    val lastSeenEpochMillis: Long,
)

internal interface AiQuotaStateStore {
    fun load(scopeKey: String): PersistentQuotaUsage?

    fun save(usages: Collection<PersistentQuotaUsage>): Boolean
}

internal class SharedPreferencesAiQuotaStateStore(
    context: Context,
) : AiQuotaStateStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(scopeKey: String): PersistentQuotaUsage? {
        val prefix = prefix(scopeKey)
        if (!preferences.contains("${prefix}last_seen")) return null
        return PersistentQuotaUsage(
            scopeKey = scopeKey,
            minuteWindowStartEpochMillis = preferences.getLong("${prefix}minute_start", 0L),
            minuteCount = preferences.getInt("${prefix}minute_count", 0).coerceAtLeast(0),
            utcDayNumber = preferences.getLong("${prefix}utc_day", 0L),
            dayCount = preferences.getInt("${prefix}day_count", 0).coerceAtLeast(0),
            lastSeenEpochMillis = preferences.getLong("${prefix}last_seen", 0L),
        )
    }

    override fun save(usages: Collection<PersistentQuotaUsage>): Boolean {
        val editor = preferences.edit()
        usages.forEach { usage ->
            val prefix = prefix(usage.scopeKey)
            editor.putLong("${prefix}minute_start", usage.minuteWindowStartEpochMillis)
            editor.putInt("${prefix}minute_count", usage.minuteCount)
            editor.putLong("${prefix}utc_day", usage.utcDayNumber)
            editor.putInt("${prefix}day_count", usage.dayCount)
            editor.putLong("${prefix}last_seen", usage.lastSeenEpochMillis)
        }
        return editor.commit()
    }

    private fun prefix(scopeKey: String) = "quota.$scopeKey."

    private companion object {
        const val PREFERENCES_NAME = "soim_ai_quota"
    }
}
