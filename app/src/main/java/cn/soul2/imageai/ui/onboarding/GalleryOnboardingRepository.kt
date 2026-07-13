package cn.soul2.imageai.ui.onboarding

import cn.soul2.imageai.data.db.dao.AppSettingDao
import cn.soul2.imageai.data.db.entity.AppSettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

interface GalleryOnboardingStore {
    val isHandled: Flow<Boolean>
    val isPermissionRequested: Flow<Boolean>

    suspend fun markHandled()

    suspend fun markPermissionRequested()
}

class GalleryOnboardingRepository(
    private val appSettingDao: AppSettingDao,
) : GalleryOnboardingStore {
    override val isHandled: Flow<Boolean> = observeFlag(HANDLED_SETTING_KEY)
    override val isPermissionRequested: Flow<Boolean> =
        observeFlag(PERMISSION_REQUESTED_SETTING_KEY)

    override suspend fun markHandled() {
        markFlag(HANDLED_SETTING_KEY)
    }

    override suspend fun markPermissionRequested() {
        markFlag(PERMISSION_REQUESTED_SETTING_KEY)
    }

    private fun observeFlag(key: String): Flow<Boolean> = appSettingDao.observeByKey(key)
        .map { setting -> setting?.valueJson == ENABLED_VALUE }
        .distinctUntilChanged()

    private suspend fun markFlag(key: String) {
        appSettingDao.upsert(
            AppSettingEntity(
                key = key,
                valueJson = ENABLED_VALUE,
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private companion object {
        const val HANDLED_SETTING_KEY = "onboarding.gallery_permission_handled"
        const val PERMISSION_REQUESTED_SETTING_KEY = "onboarding.gallery_permission_requested"
        const val ENABLED_VALUE = "true"
    }
}
