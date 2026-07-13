package cn.soul2.imageai.ui.onboarding

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.soul2.imageai.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryOnboardingRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: GalleryOnboardingRepository

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = GalleryOnboardingRepository(database.appSettingDao())
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun requestedAndHandledHistoriesPersistIndependentlyAcrossRepositoryRecreation() = runBlocking {
        assertFalse(repository.isPermissionRequested.first())
        assertFalse(repository.isHandled.first())

        repository.markPermissionRequested()

        val recreated = GalleryOnboardingRepository(database.appSettingDao())
        assertTrue(recreated.isPermissionRequested.first())
        assertFalse(recreated.isHandled.first())
        assertEquals(
            "true",
            database.appSettingDao().getByKey(PERMISSION_REQUESTED_KEY)?.valueJson,
        )
        assertNull(database.appSettingDao().getByKey(ONBOARDING_HANDLED_KEY))

        recreated.markHandled()

        assertTrue(recreated.isPermissionRequested.first())
        assertTrue(recreated.isHandled.first())
        assertEquals(
            "true",
            database.appSettingDao().getByKey(ONBOARDING_HANDLED_KEY)?.valueJson,
        )
    }

    private companion object {
        const val PERMISSION_REQUESTED_KEY = "onboarding.gallery_permission_requested"
        const val ONBOARDING_HANDLED_KEY = "onboarding.gallery_permission_handled"
    }
}
