package cn.soul2.imageai.media.permission

object GalleryPermissionRequestHistoryPolicy {
    fun canRequestAgain(
        permissionRequested: Boolean,
        rationaleResults: Iterable<Boolean>,
    ): Boolean = !permissionRequested || rationaleResults.any { it }
}
