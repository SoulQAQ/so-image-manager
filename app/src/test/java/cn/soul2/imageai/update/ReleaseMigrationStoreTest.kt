package cn.soul2.imageai.update

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cn.soul2.imageai.backup.BackupPreflightResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReleaseMigrationStoreTest {
    private lateinit var store: SharedPreferencesReleaseMigrationStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("release_migration", 0).edit().clear().commit()
        store = SharedPreferencesReleaseMigrationStore(context)
    }

    @Test
    fun persistsMigrationStageSummaryReleaseAndDownloadId() {
        val record = MigrationRecord(MigrationStage.DOWNLOADING, summary(), release(), 42L)

        store.save(record)

        assertEquals(record, store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun oneTimePromptMarkerSurvivesNewStoreInstance() {
        assertFalse(store.promptWasShown())
        store.markPromptShown()
        assertTrue(store.promptWasShown())
    }

    private fun summary() = BackupPreflightResult(
        "soim-portable-backup", 1, 12, 8, 3, 2, 2, 1, 2, false,
    )

    private fun release() = UpdateRelease(
        SemanticVersion(0, 19, 0), "v0.19.0", "SoIM v0.19.0", "说明", "2026-08-14", 
        "https://github.com/SoulQAQ/so-image-manager/releases/tag/v0.19.0",
        UpdateAsset(
            "soim-v0.19.0-release.apk",
            "https://github.com/SoulQAQ/so-image-manager/releases/download/v0.19.0/soim-v0.19.0-release.apk",
            100L,
            "a".repeat(64),
        ),
    )
}
