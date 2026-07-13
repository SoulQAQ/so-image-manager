package cn.soul2.imageai

import android.content.Context
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.data.db.AppDatabaseFactory
import cn.soul2.imageai.media.permission.GalleryPermissionMonitor
import cn.soul2.imageai.ui.onboarding.GalleryOnboardingRepository

class AppContainer(context: Context) {
    val database: AppDatabase = AppDatabaseFactory.create(context)
    val galleryPermissionMonitor = GalleryPermissionMonitor(context)
    val galleryOnboardingRepository = GalleryOnboardingRepository(database.appSettingDao())
}
