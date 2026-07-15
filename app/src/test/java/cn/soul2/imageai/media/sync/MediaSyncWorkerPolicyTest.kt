package cn.soul2.imageai.media.sync

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSyncWorkerPolicyTest {
    @Test
    fun retryAttemptContinuesDurableRunInsteadOfSubmittingRequestedModeAgain() {
        val root = generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val source = File(
            root,
            "app/src/main/java/cn/soul2/imageai/media/sync/MediaSyncWorker.kt",
        ).readText()

        assertTrue(
            source.contains(
                "runAttemptCount > 0 -> container.mediaSyncEngine.continueNextSlice()",
            ),
        )
    }

    @Test
    fun transientIoRetriesFourTimesThenPausesOnTheFifthAttempt() {
        val error = IOException("temporary")
        val result = SliceResult.Retry(run(), error)

        assertEquals(WorkerDirective.RETRY, MediaSyncWorkerPolicy.directive(result, 0))
        assertEquals(WorkerDirective.RETRY, MediaSyncWorkerPolicy.directive(result, 3))
        assertEquals(WorkerDirective.PAUSE_ERROR, MediaSyncWorkerPolicy.directive(result, 4))
    }

    @Test
    fun recoverableAndTerminalResultsDoNotCountAsWorkerFailures() {
        val run = run()

        assertEquals(
            WorkerDirective.SUCCESS,
            MediaSyncWorkerPolicy.directive(SliceResult.PausedPermission(run), 0),
        )
        assertEquals(
            WorkerDirective.SUCCESS,
            MediaSyncWorkerPolicy.directive(SliceResult.Completed(run), 0),
        )
        assertEquals(
            WorkerDirective.CONTINUE,
            MediaSyncWorkerPolicy.directive(SliceResult.More(run, cursor = null), 0),
        )
    }

    @Test
    fun onlySuccessfulRetryCompletionEnqueuesCoordinator() {
        val run = run()
        val error = IOException("temporary")

        assertEquals(
            WorkerDirective.CONTINUE,
            MediaSyncWorkerPolicy.directive(
                SliceResult.Completed(run),
                runAttemptCount = 0,
                drainAfterTerminal = true,
            ),
        )
        assertEquals(
            WorkerDirective.SUCCESS,
            MediaSyncWorkerPolicy.directive(
                SliceResult.PausedPermission(run),
                runAttemptCount = 0,
                drainAfterTerminal = true,
            ),
        )
        assertEquals(
            WorkerDirective.SUCCESS,
            MediaSyncWorkerPolicy.directive(
                SliceResult.PausedError(run, error),
                runAttemptCount = 0,
                drainAfterTerminal = true,
            ),
        )
        assertEquals(
            WorkerDirective.RETRY,
            MediaSyncWorkerPolicy.directive(
                SliceResult.Retry(run, error),
                runAttemptCount = 0,
                drainAfterTerminal = true,
            ),
        )
        assertEquals(
            WorkerDirective.PAUSE_ERROR,
            MediaSyncWorkerPolicy.directive(
                SliceResult.Retry(run, error),
                runAttemptCount = 4,
                drainAfterTerminal = true,
            ),
        )
    }

    @Test
    fun completedResumedRunContinuesOnlyWhenRequestedModeDiffers() {
        val resumedInitial = SyncRun.running(1L, SyncMode.INITIAL, 1_000L)

        assertEquals(
            WorkerDirective.CONTINUE,
            MediaSyncWorkerPolicy.directive(
                SliceResult.Completed(resumedInitial),
                runAttemptCount = 0,
                requestedMode = SyncMode.INCREMENTAL,
            ),
        )
        assertEquals(
            WorkerDirective.CONTINUE,
            MediaSyncWorkerPolicy.directive(
                SliceResult.Completed(resumedInitial),
                runAttemptCount = 0,
                requestedMode = SyncMode.RECONCILE,
            ),
        )
        assertEquals(
            WorkerDirective.SUCCESS,
            MediaSyncWorkerPolicy.directive(
                SliceResult.Completed(resumedInitial),
                runAttemptCount = 0,
                requestedMode = SyncMode.INITIAL,
            ),
        )
        assertEquals(
            WorkerDirective.SUCCESS,
            MediaSyncWorkerPolicy.directive(
                SliceResult.PausedPermission(resumedInitial),
                runAttemptCount = 0,
                requestedMode = SyncMode.INCREMENTAL,
            ),
        )
    }

    private fun run() = SyncRun.running(1L, SyncMode.INCREMENTAL, 1_000L)
}
