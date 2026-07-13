package cn.soul2.imageai.media.permission

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryPermissionPolicyTest {
    @Test
    fun `API 29 and 32 require legacy read permission`() {
        listOf(29, 32).forEach { sdkInt ->
            assertEquals(
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                GalleryPermissionPolicy.requiredPermissions(sdkInt),
            )
        }
    }

    @Test
    fun `API 33 requires media images permission`() {
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES),
            GalleryPermissionPolicy.requiredPermissions(33),
        )
    }

    @Test
    fun `API 34 and 36 require full and selected image permissions`() {
        listOf(34, 36).forEach { sdkInt ->
            assertEquals(
                listOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                ),
                GalleryPermissionPolicy.requiredPermissions(sdkInt),
            )
        }
    }

    @Test
    fun `legacy read permission resolves full access`() {
        listOf(29, 32).forEach { sdkInt ->
            assertEquals(
                GalleryAccessState.Full,
                GalleryPermissionPolicy.resolve(
                    sdkInt = sdkInt,
                    granted = setOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    canRequestAgain = false,
                ),
            )
        }
    }

    @Test
    fun `missing legacy read permission resolves denied with retry state`() {
        listOf(29, 32).forEach { sdkInt ->
            assertEquals(
                GalleryAccessState.Denied(canRequestAgain = true),
                GalleryPermissionPolicy.resolve(
                    sdkInt = sdkInt,
                    granted = emptySet(),
                    canRequestAgain = true,
                ),
            )
        }
    }

    @Test
    fun `media images permission resolves full access on API 33`() {
        assertEquals(
            GalleryAccessState.Full,
            GalleryPermissionPolicy.resolve(
                sdkInt = 33,
                granted = setOf(Manifest.permission.READ_MEDIA_IMAGES),
                canRequestAgain = false,
            ),
        )
    }

    @Test
    fun `missing media images permission resolves permanent denial on API 33`() {
        assertEquals(
            GalleryAccessState.Denied(canRequestAgain = false),
            GalleryPermissionPolicy.resolve(
                sdkInt = 33,
                granted = emptySet(),
                canRequestAgain = false,
            ),
        )
    }

    @Test
    fun `media images permission takes precedence as full access on API 34 plus`() {
        listOf(34, 36).forEach { sdkInt ->
            listOf(
                setOf(Manifest.permission.READ_MEDIA_IMAGES),
                setOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                ),
            ).forEach { granted ->
                assertEquals(
                    GalleryAccessState.Full,
                    GalleryPermissionPolicy.resolve(
                        sdkInt = sdkInt,
                        granted = granted,
                        canRequestAgain = false,
                    ),
                )
            }
        }
    }

    @Test
    fun `selected images permission resolves partial access on API 34 plus`() {
        listOf(34, 36).forEach { sdkInt ->
            assertEquals(
                GalleryAccessState.Partial,
                GalleryPermissionPolicy.resolve(
                    sdkInt = sdkInt,
                    granted = setOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
                    canRequestAgain = false,
                ),
            )
        }
    }

    @Test
    fun `missing API 34 plus permissions resolves denial`() {
        listOf(34, 36).forEach { sdkInt ->
            listOf(true, false).forEach { canRequestAgain ->
                assertEquals(
                    GalleryAccessState.Denied(canRequestAgain),
                    GalleryPermissionPolicy.resolve(
                        sdkInt = sdkInt,
                        granted = emptySet(),
                        canRequestAgain = canRequestAgain,
                    ),
                )
            }
        }
    }
}
