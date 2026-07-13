package cn.soul2.imageai

import android.app.Application
import cn.soul2.imageai.data.db.AppDatabaseFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SoImApplication : Application() {
    private val processIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        processIoScope.launch {
            AppDatabaseFactory.cleanupLegacyDatabaseIfNeeded(this@SoImApplication, container.database)
        }
    }
}
