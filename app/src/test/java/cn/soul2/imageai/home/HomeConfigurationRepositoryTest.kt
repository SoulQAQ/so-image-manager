package cn.soul2.imageai.home

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.data.db.entity.AppSettingEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeConfigurationRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: HomeConfigurationRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = HomeConfigurationRepository(database.appSettingDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun oldModuleJsonReceivesStableDefaultsAndNewValuesAreBounded() = runTest {
        database.appSettingDao().upsert(
            AppSettingEntity(
                "home.modules.v1",
                """[{"id":"old","title":"旧模块","type":"RECENT","sourceKey":"","enabled":true}]""",
                1L,
            ),
        )
        val old = repository.modules.first().single()
        assertEquals(HomeModuleSort.NEWEST, old.sort)
        assertEquals(HomeModuleLayout.STRIP, old.layout)
        assertEquals(12, old.previewCount)

        repository.saveModules(
            listOf(old.copy(sort = HomeModuleSort.SIZE, layout = HomeModuleLayout.GRID, previewCount = 999)),
        )
        val saved = repository.modules.first().single()
        assertEquals(HomeModuleSort.SIZE, saved.sort)
        assertEquals(HomeModuleLayout.GRID, saved.layout)
        assertEquals(50, saved.previewCount)
    }

    @Test
    fun recommendedThemesHaveStableIdsAndDoNotReplaceUserThemes() = runTest {
        val user = repository.saveTheme("旅行", "标签:旅行")
        repository.replaceRecommendedThemes(listOf("风景" to "分类:风景", "风景重复" to "分类:风景"))
        val first = repository.themes.first()
        repository.replaceRecommendedThemes(listOf("风景" to "分类:风景"))
        val second = repository.themes.first()

        assertTrue(first.any { it.id == user.id && !it.aiRecommended })
        assertEquals(1, first.count(UserTheme::aiRecommended))
        assertEquals(
            first.single(UserTheme::aiRecommended).id,
            second.single(UserTheme::aiRecommended).id,
        )
    }
}
