package cn.soul2.imageai.media.sync

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSyncWorkerPolicyTest {
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

    private fun run() = SyncRun.running(1L, SyncMode.INCREMENTAL, 1_000L)
}
