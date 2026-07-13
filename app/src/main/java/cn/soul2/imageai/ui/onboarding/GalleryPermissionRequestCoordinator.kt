package cn.soul2.imageai.ui.onboarding

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GalleryPermissionRequestCoordinator {
    private val mutableInFlight = MutableStateFlow(false)

    val inFlight: StateFlow<Boolean> = mutableInFlight.asStateFlow()

    suspend fun persistThenLaunch(
        persistRequestHistory: suspend () -> Unit,
        launchRequest: () -> Unit,
    ): Boolean {
        if (!mutableInFlight.compareAndSet(expect = false, update = true)) return false
        try {
            persistRequestHistory()
            launchRequest()
        } catch (error: Throwable) {
            mutableInFlight.value = false
            throw error
        }
        return true
    }

    fun complete() {
        mutableInFlight.compareAndSet(expect = true, update = false)
    }
}
