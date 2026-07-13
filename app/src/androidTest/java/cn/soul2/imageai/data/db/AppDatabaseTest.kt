package cn.soul2.imageai.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.soul2.imageai.data.db.entity.AppSettingEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var database: AppDatabase

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun settingRoundTripsThroughVersionOneSchema() = runBlocking {
        val expected = AppSettingEntity("appearance.theme", "\"system\"", 1_720_598_400_000L)
        database.appSettingDao().upsert(expected)
        assertEquals(expected, database.appSettingDao().getByKey(expected.key))
        database.appSettingDao().deleteByKey(expected.key)
        assertNull(database.appSettingDao().getByKey(expected.key))
    }
}
