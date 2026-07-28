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
