package cn.soul2.imageai.media.permission

enum class GalleryReselectionDestination {
    PermissionRequest,
    AppSettings,
}

object GalleryReselectionPolicy {
    fun destination(sdkInt: Int, accessState: GalleryAccessState): GalleryReselectionDestination =
        when {
            accessState is GalleryAccessState.Denied && accessState.canRequestAgain ->
                GalleryReselectionDestination.PermissionRequest
            sdkInt >= 34 && accessState is GalleryAccessState.Partial ->
                GalleryReselectionDestination.PermissionRequest
            else -> GalleryReselectionDestination.AppSettings
        }
}
