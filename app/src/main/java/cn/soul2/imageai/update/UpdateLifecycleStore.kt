package cn.soul2.imageai.update

import android.content.Context
import android.content.SharedPreferences

data class InstalledUpdateNotice(
    val version: SemanticVersion,
    val tagName: String,
    val releaseName: String,
    val notes: String,
)

object AutomaticUpdateCheckPolicy {
    const val MINIMUM_INTERVAL_MILLIS = 2L * 24L * 60L * 60L * 1_000L

    fun isDue(
        lastAttemptAtMillis: Long?,
        lastSuccessfulCheckAtMillis: Long?,
        nowEpochMillis: Long,
    ): Boolean {
        val lastAttemptFailed = lastAttemptAtMillis != null &&
            (lastSuccessfulCheckAtMillis == null || lastAttemptAtMillis > lastSuccessfulCheckAtMillis)
        if (lastAttemptFailed) {
            val elapsed = nowEpochMillis - checkNotNull(lastAttemptAtMillis)
            return elapsed != 0L
        }
        val reference = lastSuccessfulCheckAtMillis
            ?: lastAttemptAtMillis
            ?: return true
        val elapsed = nowEpochMillis - reference
        return elapsed < 0L || elapsed > MINIMUM_INTERVAL_MILLIS
    }
}

interface UpdateLifecycleStore {
    fun markFirstStartOfDay(epochDay: Long): Boolean
    fun lastCheckAttemptAtMillis(): Long?
    fun lastSuccessfulCheckAtMillis(): Long?
    fun recordCheckStarted(atEpochMillis: Long)
    fun recordCheckSucceeded(atEpochMillis: Long)
    fun skippedVersion(): SemanticVersion?
    fun markReleaseSkipped(version: SemanticVersion)
    fun savePreparedRelease(release: UpdateRelease)
    fun loadUnseenInstalledNotice(installedVersion: SemanticVersion): InstalledUpdateNotice?
    fun markNoticeShown(version: SemanticVersion)
}

class SharedPreferencesUpdateLifecycleStore(context: Context) : UpdateLifecycleStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun markFirstStartOfDay(epochDay: Long): Boolean = synchronized(preferences) {
        if (preferences.getLong(KEY_LAST_START_DAY, Long.MIN_VALUE) == epochDay) {
            false
        } else {
            preferences.edit().putLong(KEY_LAST_START_DAY, epochDay).commit()
        }
    }

    override fun lastCheckAttemptAtMillis(): Long? = preferences
        .getLong(KEY_LAST_CHECK_ATTEMPT_AT, Long.MIN_VALUE)
        .takeUnless { it == Long.MIN_VALUE }
        ?: legacyLastCheckAtMillis()

    override fun lastSuccessfulCheckAtMillis(): Long? = preferences
        .getLong(KEY_LAST_CHECK_SUCCEEDED_AT, Long.MIN_VALUE)
        .takeUnless { it == Long.MIN_VALUE }
        ?: legacyLastCheckAtMillis()

    override fun recordCheckStarted(atEpochMillis: Long) {
        preferences.edit().putLong(KEY_LAST_CHECK_ATTEMPT_AT, atEpochMillis).apply()
    }

    override fun recordCheckSucceeded(atEpochMillis: Long) {
        preferences.edit()
            .putLong(KEY_LAST_CHECK_ATTEMPT_AT, atEpochMillis)
            .putLong(KEY_LAST_CHECK_SUCCEEDED_AT, atEpochMillis)
            .remove(KEY_LEGACY_LAST_CHECK_AT)
            .apply()
    }

    override fun skippedVersion(): SemanticVersion? = preferences
        .getString(KEY_SKIPPED_VERSION, null)
        ?.let(SemanticVersion::parse)

    override fun markReleaseSkipped(version: SemanticVersion) {
        val editor = preferences.edit().putString(KEY_SKIPPED_VERSION, version.toString())
        if (preferences.getString(KEY_PREPARED_VERSION, null) == version.toString()) {
            editor
                .remove(KEY_PREPARED_VERSION)
                .remove(KEY_PREPARED_TAG)
                .remove(KEY_PREPARED_NAME)
                .remove(KEY_PREPARED_NOTES)
        }
        if (!editor.commit()) throw AppUpdateException("无法保存跳过的更新版本")
    }

    override fun savePreparedRelease(release: UpdateRelease) {
        val saved = preferences.edit()
            .putString(KEY_PREPARED_VERSION, release.version.toString())
            .putString(KEY_PREPARED_TAG, release.tagName)
            .putString(KEY_PREPARED_NAME, release.releaseName)
            .putString(KEY_PREPARED_NOTES, release.notes)
            .commit()
        if (!saved) throw AppUpdateException("无法保存更新说明")
    }

    override fun loadUnseenInstalledNotice(
        installedVersion: SemanticVersion,
    ): InstalledUpdateNotice? {
        val preparedVersion = preferences.getString(KEY_PREPARED_VERSION, null)
            ?.let(SemanticVersion::parse)
            ?: return null
        if (preparedVersion != installedVersion) return null
        if (preferences.getString(KEY_SHOWN_VERSION, null) == installedVersion.toString()) return null
        return InstalledUpdateNotice(
            version = preparedVersion,
            tagName = preferences.getString(KEY_PREPARED_TAG, null) ?: "v$preparedVersion",
            releaseName = preferences.getString(KEY_PREPARED_NAME, null).orEmpty(),
            notes = preferences.getString(KEY_PREPARED_NOTES, null).orEmpty(),
        )
    }

    override fun markNoticeShown(version: SemanticVersion) {
        val editor = preferences.edit().putString(KEY_SHOWN_VERSION, version.toString())
        if (preferences.getString(KEY_PREPARED_VERSION, null) == version.toString()) {
            editor
                .remove(KEY_PREPARED_VERSION)
                .remove(KEY_PREPARED_TAG)
                .remove(KEY_PREPARED_NAME)
                .remove(KEY_PREPARED_NOTES)
        }
        editor.apply()
    }

    private fun legacyLastCheckAtMillis(): Long? = preferences
        .getLong(KEY_LEGACY_LAST_CHECK_AT, Long.MIN_VALUE)
        .takeUnless { it == Long.MIN_VALUE }

    companion object {
        private const val PREFERENCES_NAME = "app_update_lifecycle"
        private const val KEY_LAST_START_DAY = "last_start_epoch_day"
        private const val KEY_LAST_CHECK_ATTEMPT_AT = "last_check_attempt_at"
        private const val KEY_LAST_CHECK_SUCCEEDED_AT = "last_check_succeeded_at"
        private const val KEY_LEGACY_LAST_CHECK_AT = "last_check_at"
        private const val KEY_SKIPPED_VERSION = "skipped_version"
        private const val KEY_PREPARED_VERSION = "prepared_version"
        private const val KEY_PREPARED_TAG = "prepared_tag"
        private const val KEY_PREPARED_NAME = "prepared_name"
        private const val KEY_PREPARED_NOTES = "prepared_notes"
        private const val KEY_SHOWN_VERSION = "shown_version"
    }
}
