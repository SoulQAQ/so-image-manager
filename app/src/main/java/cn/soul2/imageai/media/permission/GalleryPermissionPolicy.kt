package cn.soul2.imageai.media.permission

import android.Manifest

object GalleryPermissionPolicy {
    fun requiredPermissions(sdkInt: Int): List<String> = when {
        sdkInt <= 32 -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        sdkInt == 33 -> listOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
    }

    fun resolve(
        sdkInt: Int,
        granted: Set<String>,
        canRequestAgain: Boolean,
    ): GalleryAccessState = when {
        sdkInt <= 32 && Manifest.permission.READ_EXTERNAL_STORAGE in granted -> {
            GalleryAccessState.Full
        }
        sdkInt == 33 && Manifest.permission.READ_MEDIA_IMAGES in granted -> {
            GalleryAccessState.Full
        }
        sdkInt >= 34 && Manifest.permission.READ_MEDIA_IMAGES in granted -> {
            GalleryAccessState.Full
        }
        sdkInt >= 34 && Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED in granted -> {
            GalleryAccessState.Partial
        }
        else -> GalleryAccessState.Denied(canRequestAgain)
    }
}
