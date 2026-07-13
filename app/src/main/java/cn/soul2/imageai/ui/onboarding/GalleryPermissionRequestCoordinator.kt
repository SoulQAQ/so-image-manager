package cn.soul2.imageai.ui.onboarding

object GalleryPermissionRequestCoordinator {
    suspend fun persistThenLaunch(
        persistRequestHistory: suspend () -> Unit,
        launchRequest: () -> Unit,
    ) {
        persistRequestHistory()
        launchRequest()
    }
}
