package cn.soul2.imageai.ui.onboarding

import cn.soul2.imageai.data.db.dao.AppSettingDao
import cn.soul2.imageai.data.db.entity.AppSettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class GalleryOnboardingRepository(
    private val appSettingDao: AppSettingDao,
) {
    val isHandled: Flow<Boolean> = appSettingDao.observeByKey(SETTING_KEY)
        .map { setting -> setting?.valueJson == HANDLED_VALUE }
        .distinctUntilChanged()

    suspend fun markHandled() {
        appSettingDao.upsert(
            AppSettingEntity(
                key = SETTING_KEY,
                valueJson = HANDLED_VALUE,
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private companion object {
        const val SETTING_KEY = "onboarding.gallery_permission_handled"
        const val HANDLED_VALUE = "true"
    }
}
