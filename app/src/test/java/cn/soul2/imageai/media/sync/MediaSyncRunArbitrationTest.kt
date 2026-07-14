package cn.soul2.imageai.media.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.soul2.imageai.data.db.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaSyncRunArbitrationTest {
    private lateinit var database: AppDatabase
    private lateinit var store: RoomMediaSyncStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = RoomMediaSyncStore(database.mediaSyncDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun runningRunWinsWhileRequestedModesAreDurablyQueuedAndDeduplicated() = runTest {
        val reconciliation = requireNotNull(
            store.enqueueAndClaimRun(SyncMode.RECONCILE, nowEpochMillis = 1_000L),
        )
        assertEquals(SyncRunState.RUNNING, reconciliation.state)

        assertEquals(
            reconciliation.runId,
            store.enqueueAndClaimRun(SyncMode.INCREMENTAL, 2_000L)?.runId,
        )
        assertEquals(
            reconciliation.runId,
            store.enqueueAndClaimRun(SyncMode.INCREMENTAL, 3_000L)?.runId,
        )
        assertEquals(
            reconciliation.runId,
            store.enqueueAndClaimRun(SyncMode.RECONCILE, 4_000L)?.runId,
        )
        assertEquals(
            listOf(
                RunState(SyncMode.RECONCILE, SyncRunState.RUNNING),
                RunState(SyncMode.INCREMENTAL, SyncRunState.QUEUED),
                RunState(SyncMode.RECONCILE, SyncRunState.QUEUED),
            ),
            runStates(),
        )

        store.updateRun(reconciliation.succeeded(5_000L))
        val incremental = requireNotNull(store.enqueueAndClaimRun(null, 6_000L))
        assertEquals(SyncMode.INCREMENTAL, incremental.mode)
        assertEquals(1, runStates().count { it.state == SyncRunState.RUNNING })

        store.updateRun(incremental.succeeded(7_000L))
        val queuedReconciliation = requireNotNull(store.enqueueAndClaimRun(null, 8_000L))
        assertEquals(SyncMode.RECONCILE, queuedReconciliation.mode)
        assertEquals(1, runStates().count { it.state == SyncRunState.RUNNING })

        store.updateRun(queuedReconciliation.succeeded(9_000L))
        assertNull(store.enqueueAndClaimRun(null, 10_000L))
        assertEquals(0, runStates().count { it.state == SyncRunState.RUNNING })
    }

    private fun runStates(): List<RunState> {
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT mode, state FROM media_sync_run ORDER BY run_id",
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        RunState(
                            mode = SyncMode.valueOf(it.getString(0)),
                            state = SyncRunState.valueOf(it.getString(1)),
                        ),
                    )
                }
            }
        }
    }

    private data class RunState(
        val mode: SyncMode,
        val state: SyncRunState,
    )
}
