package cn.soul2.imageai.media.store

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cn.soul2.imageai.media.sync.SyncMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class MediaStoreSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun gatewayReadsMetadataFromARealMediaStoreFixture() {
        grantGalleryReadPermission()
        val resolver = context.contentResolver
        val displayName = "soim-smoke-${System.nanoTime()}.png"
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val fixtureUri = requireNotNull(
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.DATE_MODIFIED,
                        System.currentTimeMillis() / 1_000L,
                    )
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/SoIMTest",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )

        try {
            resolver.openOutputStream(fixtureUri, "w").use { output ->
                requireNotNull(output)
                val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                try {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                } finally {
                    bitmap.recycle()
                }
            }
            resolver.update(
                fixtureUri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )

            val gateway = AndroidMediaStoreGateway(context)
            assertTrue(MediaStore.VOLUME_EXTERNAL_PRIMARY in gateway.externalVolumes())
            val fixture = findFixture(gateway, displayName)

            assertEquals(fixtureUri.toString(), fixture.contentUri)
            assertEquals("image/png", fixture.mimeType)
            assertEquals(MediaStore.VOLUME_EXTERNAL_PRIMARY, fixture.volumeName)
        } finally {
            resolver.delete(fixtureUri, null, null)
        }
    }

    private fun grantGalleryReadPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            permission,
        )
    }

    private fun findFixture(
        gateway: AndroidMediaStoreGateway,
        displayName: String,
    ): MediaStoreImage {
        var cursor: MediaStoreCursor? = null
        repeat(MAX_PAGES) {
            val page = gateway.readPage(
                volume = MediaStore.VOLUME_EXTERNAL_PRIMARY,
                mode = SyncMode.INITIAL,
                cursor = cursor,
                limit = PAGE_SIZE,
            )
            page.images.firstOrNull { image -> image.displayName == displayName }?.let {
                return it
            }
            val nextCursor = page.nextCursor
            if (!page.hasMore || nextCursor == null || nextCursor == cursor) {
                throw AssertionError("MediaStore fixture was not returned by the gateway")
            }
            cursor = nextCursor
        }
        throw AssertionError("MediaStore fixture was not found within the bounded scan")
    }

    private companion object {
        const val PAGE_SIZE = 250
        const val MAX_PAGES = 100
    }
}
