package cn.soul2.imageai.ai.quota

import android.content.Context

data class DailyTokenUsage(val utcDayNumber: Long, val tokens: Long, val known: Boolean)

class AiTokenUsageLedger(context: Context) {
    private val preferences = context.getSharedPreferences("soim_ai_token_usage", Context.MODE_PRIVATE)

    @Synchronized
    fun current(nowEpochMillis: Long = System.currentTimeMillis()): DailyTokenUsage {
        val day = nowEpochMillis.coerceAtLeast(0L) / MILLIS_PER_DAY
        val storedDay = preferences.getLong(KEY_DAY, -1L)
        return if (storedDay == day) {
            DailyTokenUsage(day, preferences.getLong(KEY_TOKENS, 0L).coerceAtLeast(0L),
                preferences.getBoolean(KEY_KNOWN, true))
        } else DailyTokenUsage(day, 0L, true)
    }

    @Synchronized
    fun record(tokens: Long?, nowEpochMillis: Long = System.currentTimeMillis()): DailyTokenUsage {
        val current = current(nowEpochMillis)
        val next = DailyTokenUsage(
            current.utcDayNumber,
            current.tokens + (tokens ?: 0L).coerceAtLeast(0L),
            current.known && tokens != null,
        )
        preferences.edit().putLong(KEY_DAY, next.utcDayNumber).putLong(KEY_TOKENS, next.tokens)
            .putBoolean(KEY_KNOWN, next.known).apply()
        return next
    }

    fun isLimitReached(limit: Long, nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        limit > 0L && current(nowEpochMillis).tokens >= limit

    private companion object {
        const val KEY_DAY = "utc_day"
        const val KEY_TOKENS = "tokens"
        const val KEY_KNOWN = "known"
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
