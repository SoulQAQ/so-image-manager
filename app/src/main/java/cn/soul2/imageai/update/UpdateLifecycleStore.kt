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

    fun isDue(lastCheckAtMillis: Long?, nowEpochMillis: Long): Boolean {
        if (lastCheckAtMillis == null) return true
        val elapsed = nowEpochMillis - lastCheckAtMillis
        return elapsed < 0L || elapsed > MINIMUM_INTERVAL_MILLIS
    }
}

interface UpdateLifecycleStore {
    fun markFirstStartOfDay(epochDay: Long): Boolean
    fun lastCheckAtMillis(): Long?
    fun recordCheckStarted(atEpochMillis: Long)
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

    override fun lastCheckAtMillis(): Long? = preferences
        .getLong(KEY_LAST_CHECK_AT, Long.MIN_VALUE)
        .takeUnless { it == Long.MIN_VALUE }

    override fun recordCheckStarted(atEpochMillis: Long) {
        preferences.edit().putLong(KEY_LAST_CHECK_AT, atEpochMillis).apply()
    }

    override fun savePreparedRelease(release: UpdateRelease) {
        preferences.edit()
            .putString(KEY_PREPARED_VERSION, release.version.toString())
            .putString(KEY_PREPARED_TAG, release.tagName)
            .putString(KEY_PREPARED_NAME, release.releaseName)
            .putString(KEY_PREPARED_NOTES, release.notes)
            .apply()
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

    companion object {
        private const val PREFERENCES_NAME = "app_update_lifecycle"
        private const val KEY_LAST_START_DAY = "last_start_epoch_day"
        private const val KEY_LAST_CHECK_AT = "last_check_at"
        private const val KEY_PREPARED_VERSION = "prepared_version"
        private const val KEY_PREPARED_TAG = "prepared_tag"
        private const val KEY_PREPARED_NAME = "prepared_name"
        private const val KEY_PREPARED_NOTES = "prepared_notes"
        private const val KEY_SHOWN_VERSION = "shown_version"
    }
}
