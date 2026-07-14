package cn.soul2.imageai

import android.content.Context
import androidx.work.WorkManager
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.data.db.AppDatabaseFactory
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.gallery.RoomGalleryRepository
import cn.soul2.imageai.media.permission.GalleryPermissionMonitor
import cn.soul2.imageai.media.store.AndroidMediaStoreGateway
import cn.soul2.imageai.media.store.MediaStoreGateway
import cn.soul2.imageai.media.sync.MediaStoreObserver
import cn.soul2.imageai.media.sync.GallerySyncAccessCoordinator
import cn.soul2.imageai.media.sync.MediaSyncEngine
import cn.soul2.imageai.media.sync.MediaSyncPermissionSource
import cn.soul2.imageai.media.sync.MediaSyncScheduler
import cn.soul2.imageai.media.sync.MediaSyncStore
import cn.soul2.imageai.media.sync.RoomMediaSyncStore
import cn.soul2.imageai.media.sync.WorkManagerSyncWorkBackend
import cn.soul2.imageai.ui.onboarding.GalleryOnboardingRepository
import kotlinx.coroutines.CoroutineScope

class AppContainer(
    context: Context,
    processScope: CoroutineScope,
) {
    private val applicationContext = context.applicationContext

    val database: AppDatabase = AppDatabaseFactory.create(applicationContext)
    val galleryRepository: GalleryRepository = RoomGalleryRepository(database.imageDao())
    val gallerySyncRuns = database.mediaSyncDao().observeCurrentRun()
    val galleryLastSyncCompletedAt = database.mediaSyncDao().observeLastCompletedAt()
    val galleryUnavailableCounts = database.imageDao().observeUnavailableCount()
    val galleryPermissionMonitor = GalleryPermissionMonitor(applicationContext)
    val galleryOnboardingRepository = GalleryOnboardingRepository(database.appSettingDao())
    val mediaStoreGateway: MediaStoreGateway = AndroidMediaStoreGateway(applicationContext)
    val mediaSyncStore: MediaSyncStore = RoomMediaSyncStore(database.mediaSyncDao())
    val mediaSyncEngine = MediaSyncEngine(
        gateway = mediaStoreGateway,
        store = mediaSyncStore,
        permissionSource = MediaSyncPermissionSource { galleryPermissionMonitor.state.value },
    )
    val mediaSyncScheduler = MediaSyncScheduler(
        WorkManagerSyncWorkBackend(WorkManager.getInstance(applicationContext)),
    )
    val gallerySyncAccessCoordinator = GallerySyncAccessCoordinator(
        store = mediaSyncStore,
        scheduler = mediaSyncScheduler,
    )
    val mediaStoreObserver = MediaStoreObserver(
        context = applicationContext,
        scheduler = mediaSyncScheduler,
        scope = processScope,
    )
}
