package cn.soul2.imageai.update

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppUpdateManagerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun discoversNewerReleaseAndCompletesDurableDownload() = runTest {
        val release = release()
        val downloads = FakeDownloads(
            statuses = ArrayDeque(
                listOf(
                    UpdateDownloadStatus.Active(25L, 100L),
                    UpdateDownloadStatus.Successful,
                ),
            ),
        )
        val store = MemoryPendingStore()
        val manager = AppUpdateManager(
            context = context,
            scope = this,
            source = ReleaseUpdateSource { release },
            downloads = downloads,
            verifier = FakeVerifier(SemanticVersion(0, 16, 0)),
            pendingStore = store,
        )

        manager.checkForUpdate()
        advanceUntilIdle()
        assertEquals(AppUpdateState.Available(release), manager.state.value)

        manager.downloadUpdate()
        runCurrent()
        assertEquals(AppUpdateState.Downloading(release, 25L, 100L), manager.state.value)
        assertEquals(7L, store.pending?.downloadId)
        advanceTimeBy(751L)
        runCurrent()

        assertTrue(manager.state.value is AppUpdateState.Ready)
        assertEquals(null, store.pending)
    }

    @Test
    fun doesNotOfferOlderReleaseAndSurfacesSourceFailure() = runTest {
        val upToDate = AppUpdateManager(
            context = context,
            scope = this,
            source = ReleaseUpdateSource { release(SemanticVersion(0, 15, 0)) },
            downloads = FakeDownloads(),
            verifier = FakeVerifier(SemanticVersion(0, 16, 0)),
            pendingStore = MemoryPendingStore(),
        )
        upToDate.checkForUpdate()
        advanceUntilIdle()
        assertEquals(AppUpdateState.UpToDate("0.16.0"), upToDate.state.value)

        val failed = AppUpdateManager(
            context = context,
            scope = this,
            source = ReleaseUpdateSource { throw AppUpdateException("网络不可用") },
            downloads = FakeDownloads(),
            verifier = FakeVerifier(SemanticVersion(0, 16, 0)),
            pendingStore = MemoryPendingStore(),
        )
        failed.checkForUpdate()
        advanceUntilIdle()
        assertEquals(AppUpdateState.Failed("网络不可用"), failed.state.value)
    }

    @Test
    fun resumesPersistedDownloadAfterProcessRecreation() = runTest {
        val release = release()
        val store = MemoryPendingStore().apply {
            pending = PendingUpdate(downloadId = 7L, release = release)
        }
        val manager = AppUpdateManager(
            context = context,
            scope = this,
            source = ReleaseUpdateSource { release },
            downloads = FakeDownloads(ArrayDeque(listOf(UpdateDownloadStatus.Successful))),
            verifier = FakeVerifier(SemanticVersion(0, 16, 0)),
            pendingStore = store,
        )

        runCurrent()

        assertTrue(manager.state.value is AppUpdateState.Ready)
        assertEquals(null, store.pending)
    }

    @Test
    fun automaticCheckRunsOncePerDayAndOnlyAfterMoreThanTwoDays() = runTest {
        val dayMillis = 24L * 60L * 60L * 1_000L
        var now = 10L * dayMillis
        var requests = 0
        val lifecycle = MemoryLifecycleStore()
        val manager = AppUpdateManager(
            context = context,
            scope = this,
            source = ReleaseUpdateSource {
                requests += 1
                release(SemanticVersion(0, 16, 0))
            },
            downloads = FakeDownloads(),
            verifier = FakeVerifier(SemanticVersion(0, 17, 0)),
            pendingStore = MemoryPendingStore(),
            lifecycleStore = lifecycle,
            nowEpochMillis = { now },
            epochDayAt = { it / dayMillis },
        )

        manager.onAppStarted()
        advanceUntilIdle()
        assertEquals(1, requests)

        manager.onAppStarted()
        advanceUntilIdle()
        assertEquals(1, requests)

        now += dayMillis
        manager.onAppStarted()
        advanceUntilIdle()
        assertEquals(1, requests)

        now += dayMillis
        manager.onAppStarted()
        advanceUntilIdle()
        assertEquals(1, requests)

        now += dayMillis
        manager.onAppStarted()
        advanceUntilIdle()
        assertEquals(2, requests)
    }

    @Test
    fun preparedReleaseNotesAppearOnceAfterTargetVersionIsInstalled() = runTest {
        val target = release()
        val lifecycle = MemoryLifecycleStore()
        val downloadManager = AppUpdateManager(
            context = context,
            scope = this,
            source = ReleaseUpdateSource { target },
            downloads = FakeDownloads(ArrayDeque(listOf(UpdateDownloadStatus.Successful))),
            verifier = FakeVerifier(SemanticVersion(0, 16, 0)),
            pendingStore = MemoryPendingStore(),
            lifecycleStore = lifecycle,
        )
        downloadManager.checkForUpdate()
        advanceUntilIdle()
        downloadManager.downloadUpdate()
        advanceUntilIdle()

        val installedManager = AppUpdateManager(
            context = context,
            scope = this,
            source = ReleaseUpdateSource { target },
            downloads = FakeDownloads(),
            verifier = FakeVerifier(target.version),
            pendingStore = MemoryPendingStore(),
            lifecycleStore = lifecycle,
        )
        installedManager.onAppStarted()
        advanceUntilIdle()

        assertEquals(target.version, installedManager.installedUpdateNotice.value?.version)
        assertEquals(target.notes, installedManager.installedUpdateNotice.value?.notes)

        installedManager.dismissInstalledUpdateNotice()
        installedManager.onAppStarted()
        assertEquals(null, installedManager.installedUpdateNotice.value)
    }

    @Test
    fun automaticCheckRequiresStrictlyMoreThanTwoDays() {
        val interval = AutomaticUpdateCheckPolicy.MINIMUM_INTERVAL_MILLIS
        assertEquals(false, AutomaticUpdateCheckPolicy.isDue(1_000L, 1_000L + interval))
        assertEquals(true, AutomaticUpdateCheckPolicy.isDue(1_000L, 1_001L + interval))
        assertEquals(true, AutomaticUpdateCheckPolicy.isDue(2_000L, 1_000L))
    }

    @Test
    fun sharedPreferencesLifecycleStorePersistsAndConsumesTargetReleaseNotice() {
        context.getSharedPreferences("app_update_lifecycle", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val target = release()
        val store = SharedPreferencesUpdateLifecycleStore(context)

        assertTrue(store.markFirstStartOfDay(42L))
        assertEquals(false, store.markFirstStartOfDay(42L))
        store.recordCheckStarted(123_456L)
        assertEquals(123_456L, store.lastCheckAtMillis())

        store.savePreparedRelease(target)
        assertEquals(null, store.loadUnseenInstalledNotice(SemanticVersion(0, 16, 0)))
        assertEquals(target.notes, store.loadUnseenInstalledNotice(target.version)?.notes)

        store.markNoticeShown(target.version)
        assertEquals(null, store.loadUnseenInstalledNotice(target.version))
    }

    private class FakeVerifier(private val installed: SemanticVersion) : UpdateArtifactVerifier {
        override fun installedVersion() = InstalledAppVersion(installed, installed.toString(), 21L)
        override fun verify(file: File, release: UpdateRelease): File = file
    }

    private class FakeDownloads(
        private val statuses: ArrayDeque<UpdateDownloadStatus> = ArrayDeque(),
    ) : UpdateDownloadGateway {
        private val file = File("build/test-update.apk")
        override fun enqueue(release: UpdateRelease): Long = 7L
        override fun status(downloadId: Long): UpdateDownloadStatus =
            if (statuses.isEmpty()) UpdateDownloadStatus.Missing else statuses.removeFirst()
        override fun cancel(downloadId: Long) = Unit
        override fun fileFor(asset: UpdateAsset): File = file
    }

    private class MemoryPendingStore : PendingUpdateStore {
        var pending: PendingUpdate? = null
        override fun load(): PendingUpdate? = pending
        override fun save(pending: PendingUpdate) {
            this.pending = pending
        }
        override fun clear() {
            pending = null
        }
    }

    private class MemoryLifecycleStore : UpdateLifecycleStore {
        private var lastStartDay: Long? = null
        private var lastCheckAt: Long? = null
        private var prepared: InstalledUpdateNotice? = null
        private var shownVersion: SemanticVersion? = null

        override fun markFirstStartOfDay(epochDay: Long): Boolean {
            if (lastStartDay == epochDay) return false
            lastStartDay = epochDay
            return true
        }

        override fun lastCheckAtMillis(): Long? = lastCheckAt

        override fun recordCheckStarted(atEpochMillis: Long) {
            lastCheckAt = atEpochMillis
        }

        override fun savePreparedRelease(release: UpdateRelease) {
            prepared = InstalledUpdateNotice(
                release.version,
                release.tagName,
                release.releaseName,
                release.notes,
            )
        }

        override fun loadUnseenInstalledNotice(
            installedVersion: SemanticVersion,
        ): InstalledUpdateNotice? = prepared?.takeIf {
            it.version == installedVersion && shownVersion != installedVersion
        }

        override fun markNoticeShown(version: SemanticVersion) {
            shownVersion = version
            if (prepared?.version == version) prepared = null
        }
    }

    private fun release(version: SemanticVersion = SemanticVersion(0, 17, 0)) = UpdateRelease(
        version = version,
        tagName = "v$version",
        releaseName = "SoIM v$version",
        notes = "版本说明",
        publishedAt = "2026-07-29T00:00:00Z",
        pageUrl = "https://github.com/SoulQAQ/so-image-manager/releases/tag/v$version",
        asset = UpdateAsset(
            name = "soim-v$version-debug.apk",
            downloadUrl = "https://github.com/SoulQAQ/so-image-manager/releases/download/v$version/soim-v$version-debug.apk",
            sizeBytes = 100L,
            sha256 = "a".repeat(64),
        ),
    )
}
