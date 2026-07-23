package cn.soul2.imageai.gallery

import android.app.Application
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.soul2.imageai.analysis.EffectiveImageMetadata
import cn.soul2.imageai.analysis.EffectiveMetadataReader
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.data.db.entity.EffectiveCaptionSource
import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.ImagePartition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomGalleryRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomGalleryRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomGalleryRepository(database.imageDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun recentAndAllExposeOnlyAvailableImagesInStableDescendingOrder() = runTest {
        database.imageDao().upsert(
            listOf(
                image(localId = 1L, mediaStoreId = 1L, sortTime = 400L, available = false),
                image(localId = 2L, mediaStoreId = 2L, sortTime = 300L),
                image(localId = 3L, mediaStoreId = 11L, sortTime = 200L, volume = "a"),
                image(localId = 4L, mediaStoreId = 10L, sortTime = 200L, volume = "b"),
                image(localId = 5L, mediaStoreId = 10L, sortTime = 200L, volume = "a"),
                image(localId = 6L, mediaStoreId = 99L, sortTime = 100L),
            ),
        )

        listOf(GallerySource.Recent, GallerySource.All).forEach { source ->
            assertEquals(
                listOf(2L, 3L, 4L, 5L, 6L),
                repository.observe(GalleryQuery(source)).asSnapshot().map { it.localId },
            )
        }
    }

    @Test
    fun pendingMissingImagesAreRetainedButHiddenFromGalleryAndDetail() = runTest {
        database.imageDao().upsert(
            listOf(
                image(localId = 1L, mediaStoreId = 1L),
                image(localId = 2L, mediaStoreId = 2L).copy(
                    missingCandidateSinceEpochMillis = 500L,
                    missingObservationCount = 1,
                ),
            ),
        )

        assertEquals(
            listOf(1L),
            repository.observe(GalleryQuery(GallerySource.All)).asSnapshot().map { it.localId },
        )
        assertEquals(1, repository.observeCount().take(1).toList().single())
        assertEquals(null, repository.observeImage(2L).take(1).toList().single())
    }

    @Test
    fun detailWindowUsesTheSameStableOrderAsTheGallery() = runTest {
        database.imageDao().upsert(
            listOf(
                image(localId = 1L, mediaStoreId = 1L, sortTime = 300L),
                image(localId = 2L, mediaStoreId = 9L, sortTime = 200L, volume = "b"),
                image(localId = 3L, mediaStoreId = 9L, sortTime = 200L, volume = "a"),
                image(localId = 4L, mediaStoreId = 8L, sortTime = 200L, volume = "z"),
                image(localId = 5L, mediaStoreId = 5L, sortTime = 100L),
            ),
        )

        val window = requireNotNull(
            repository.observeImageWindow(3L).take(1).toList().single(),
        )

        assertEquals(2L, window.previous?.localId)
        assertEquals(3L, window.current.localId)
        assertEquals(4L, window.next?.localId)
    }

    @Test
    fun pagingContractMatchesDenseGalleryRequirements() {
        assertEquals(60, RoomGalleryRepository.PAGING_CONFIG.pageSize)
        assertEquals(120, RoomGalleryRepository.PAGING_CONFIG.initialLoadSize)
        assertEquals(20, RoomGalleryRepository.PAGING_CONFIG.prefetchDistance)
        assertEquals(false, RoomGalleryRepository.PAGING_CONFIG.enablePlaceholders)
    }

    @Test
    fun availableCountUpdatesWhenAvailabilityChanges() = runTest {
        val counts = Channel<Int>(Channel.UNLIMITED)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeCount().take(3).collect(counts::send)
        }

        assertEquals(0, counts.receive())
        database.imageDao().upsert(listOf(image(localId = 7L, mediaStoreId = 7L)))
        assertEquals(1, counts.receive())
        database.imageDao().upsert(
            listOf(image(localId = 7L, mediaStoreId = 7L, available = false)),
        )

        assertEquals(0, counts.receive())
        collection.join()
        counts.close()
    }

    @Test
    fun detailMapsMediaStoreMetadataAndBecomesNullWhenUnavailable() = runTest {
        val entity = image(
            localId = 8L,
            mediaStoreId = 8L,
            sortTime = 123_000L,
            width = 3_024,
            height = 4_032,
        )
        database.imageDao().upsert(listOf(entity))
        val details = Channel<GalleryImage?>(Channel.UNLIMITED)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeImage(entity.localId).take(2).collect(details::send)
        }

        val availableDetail = details.receive()
        database.imageDao().upsert(listOf(entity.copy(availability = ImageAvailability.MEDIA_MISSING)))
        val unavailableDetail = details.receive()

        assertEquals(
            GalleryImage(
                localId = 8L,
                contentUri = "content://media/external/images/media/8",
                displayName = "8.jpg",
                mimeType = "image/jpeg",
                width = 3_024,
                height = 4_032,
                sizeBytes = 8_000L,
                capturedAtEpochMillis = 123_000L,
                addedAtEpochMillis = 123_000L,
                modifiedAtEpochMillis = 123_000L,
                bucketName = "相机",
                isFavorite = false,
            ),
            availableDetail,
        )
        assertEquals(null, unavailableDetail)
        collection.join()
        details.close()
    }

    @Test
    fun effectiveMetadataDelegatesToTheInjectedCanonicalReader() = runTest {
        val expected = EffectiveImageMetadata(
            imageLocalId = 8L,
            activeAnalysis = null,
            caption = null,
            captionSource = EffectiveCaptionSource.NONE,
            projectionGeneration = 3L,
            updatedAtEpochMillis = 100L,
            terms = emptyList(),
            captionCorrection = null,
            termOverrides = emptyList(),
            history = emptyList(),
        )
        val repositoryWithReader = RoomGalleryRepository(
            database.imageDao(),
            EffectiveMetadataReader { flowOf(expected) },
        )

        assertEquals(expected, repositoryWithReader.observeEffectiveMetadata(8L).first())
    }

    @Test
    fun privateGalleryAndDetailWindowNeverExposeMainImages() = runTest {
        database.imageDao().upsert(
            listOf(
                image(localId = 1L, mediaStoreId = 1L, sortTime = 300L),
                image(localId = 2L, mediaStoreId = 2L, sortTime = 200L).copy(partition = ImagePartition.PRIVATE),
                image(localId = 3L, mediaStoreId = 3L, sortTime = 100L).copy(partition = ImagePartition.PRIVATE),
                image(localId = 4L, mediaStoreId = 4L, sortTime = 50L).copy(partition = ImagePartition.PRIVATE_UNANALYZABLE),
            ),
        )

        assertEquals(
            listOf(2L, 3L),
            repository.observe(GalleryQuery(GallerySource.Private)).asSnapshot().map { it.localId },
        )
        assertEquals(
            listOf(4L),
            repository.observe(GalleryQuery(GallerySource.PrivateUnanalyzable)).asSnapshot().map { it.localId },
        )
        val window = requireNotNull(repository.observeImageWindow(3L, GallerySource.Private).first())
        assertEquals(2L, window.previous?.localId)
        assertEquals(3L, window.current.localId)
        assertEquals(null, window.next)
        assertEquals(null, repository.observeImage(1L, GallerySource.Private).first())
    }

    @Test
    fun movingMainImagesToPrivateRemovesTheirSearchableGalleryProjection() = runTest {
        database.imageDao().upsert(listOf(image(localId = 9L, mediaStoreId = 9L)))

        database.imageDao().moveMainImagesToPrivate(listOf(9L))

        assertEquals(emptyList<Long>(), repository.observe(GalleryQuery(GallerySource.All)).asSnapshot().map { it.localId })
        assertEquals(listOf(9L), repository.observe(GalleryQuery(GallerySource.Private)).asSnapshot().map { it.localId })
    }

    private fun image(
        localId: Long,
        mediaStoreId: Long,
        sortTime: Long = 100L,
        volume: String = "external",
        available: Boolean = true,
        width: Int = 1_920,
        height: Int = 1_080,
    ) = ImageEntity(
        localId = localId,
        volumeName = volume,
        mediaStoreId = mediaStoreId,
        contentUri = "content://media/$volume/images/media/$mediaStoreId",
        displayName = "$mediaStoreId.jpg",
        mimeType = "image/jpeg",
        width = width,
        height = height,
        sizeBytes = mediaStoreId * 1_000L,
        capturedAtEpochMillis = sortTime,
        addedAtEpochMillis = sortTime,
        modifiedAtEpochMillis = sortTime,
        sortTimeEpochMillis = sortTime,
        bucketId = 1L,
        bucketName = "相机",
        isFavorite = false,
        quickFingerprint = "fingerprint-$volume-$mediaStoreId",
        availability = if (available) ImageAvailability.AVAILABLE else ImageAvailability.MEDIA_MISSING,
        lastSeenSyncRunId = null,
        missingCandidateSinceEpochMillis = null,
        missingObservationCount = 0,
    )
}
