package cn.soul2.imageai.performance

import android.app.Application
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.soul2.imageai.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GalleryRoomScaleBenchmarkTest {
    @Test
    fun measuresTenFiftyAndHundredThousandImageDatabases() = runBlocking {
        assumeTrue(System.getProperty("soim.scaleBenchmark") == "true")
        listOf(10_000, 50_000, 100_000).forEach { count -> benchmark(count) }
    }

    private suspend fun benchmark(count: Int) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "soim-scale-$count.db"
        context.deleteDatabase(name)
        val beforeMemory = usedMemory()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            val insertStarted = System.nanoTime()
            for (chunk in GalleryScaleFixture.images(count).chunked(1_000)) {
                database.imageDao().upsert(chunk)
            }
            val insertMillis = elapsedMillis(insertStarted)
            val pageStarted = System.nanoTime()
            val page = database.imageDao().pagingRecent().load(
                PagingSource.LoadParams.Refresh(key = null, loadSize = 120, placeholdersEnabled = false),
            ) as PagingSource.LoadResult.Page
            val pageMillis = elapsedMillis(pageStarted)
            val db = context.getDatabasePath(name)
            val bytes = listOf(db, java.io.File(db.path + "-wal"), java.io.File(db.path + "-shm"))
                .filter(java.io.File::isFile).sumOf(java.io.File::length)
            val memoryDelta = (usedMemory() - beforeMemory).coerceAtLeast(0L)

            assertEquals(120, page.data.size)
            assertTrue("database exceeds 10 KB/image", bytes <= count * 10_240L)
            println(
                "SOIM_SCALE count=$count insertMs=$insertMillis firstPageMs=$pageMillis " +
                    "databaseBytes=$bytes memoryDeltaBytes=$memoryDelta",
            )
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun elapsedMillis(started: Long) = (System.nanoTime() - started) / 1_000_000L
    private fun usedMemory(): Long = Runtime.getRuntime().run { totalMemory() - freeMemory() }
}
