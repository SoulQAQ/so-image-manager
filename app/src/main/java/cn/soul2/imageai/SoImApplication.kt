package cn.soul2.imageai

import android.app.Application
import android.util.Log
import cn.soul2.imageai.data.db.AppDatabaseFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SoImApplication : Application() {
    private val processIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, processIoScope)
        container.mediaStoreObserver.register()
        container.mediaSyncScheduler.ensurePeriodicReconciliation()
        launchLegacyDatabaseCleanup(
            scope = processIoScope,
            cleanup = {
                AppDatabaseFactory.cleanupLegacyDatabaseIfNeeded(
                    this@SoImApplication,
                    container.database,
                )
            },
        )
    }
}

internal fun launchLegacyDatabaseCleanup(
    scope: CoroutineScope,
    cleanup: suspend () -> Unit,
    logWarning: (String) -> Unit = { message -> Log.w("SoImMaintenance", message) },
): Job = scope.launch {
    try {
        cleanup()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        logWarning("Legacy database cleanup failed; will retry")
    }
}
