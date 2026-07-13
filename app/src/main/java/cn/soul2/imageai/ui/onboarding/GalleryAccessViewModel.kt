package cn.soul2.imageai.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.media.permission.GalleryPermissionMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GalleryAccessUiState(
    val galleryAccessState: GalleryAccessState,
    val onboardingHandled: Boolean?,
    val permissionRequested: Boolean?,
    val permissionHistoryApplied: Boolean,
    val isPermissionRecovery: Boolean,
) {
    val showOnboarding: Boolean
        get() = galleryAccessState is GalleryAccessState.Denied &&
            (onboardingHandled == false || isPermissionRecovery)

    val isLoading: Boolean
        get() = onboardingHandled == null ||
            permissionRequested == null ||
            !permissionHistoryApplied
}

private data class PersistedGalleryPermissionHistory(
    val onboardingHandled: Boolean,
    val permissionRequested: Boolean,
)

private data class GalleryPermissionSessionState(
    val onboardingHandled: Boolean,
    val permissionRequested: Boolean,
    val isPermissionRecovery: Boolean,
)

class GalleryAccessViewModel(
    private val permissionMonitor: GalleryPermissionMonitor,
    private val onboardingRepository: GalleryOnboardingRepository,
) : ViewModel() {
    private val handledInSession = MutableStateFlow(false)
    private val requestedInSession = MutableStateFlow(false)
    private val permissionRecovery = MutableStateFlow(false)
    private val permissionHistoryApplied = MutableStateFlow(false)

    private val persistedHistory = combine(
        onboardingRepository.isHandled,
        onboardingRepository.isPermissionRequested,
    ) { handled, requested ->
        PersistedGalleryPermissionHistory(handled, requested)
    }

    private val sessionState = combine(
        handledInSession,
        requestedInSession,
        permissionRecovery,
    ) { handled, requested, recovery ->
        GalleryPermissionSessionState(handled, requested, recovery)
    }

    val uiState: StateFlow<GalleryAccessUiState> = combine(
        permissionMonitor.state,
        persistedHistory,
        sessionState,
        permissionHistoryApplied,
    ) { accessState, persisted, session, historyApplied ->
        GalleryAccessUiState(
            galleryAccessState = accessState,
            onboardingHandled = persisted.onboardingHandled || session.onboardingHandled,
            permissionRequested = persisted.permissionRequested || session.permissionRequested,
            permissionHistoryApplied = historyApplied,
            isPermissionRecovery = session.isPermissionRecovery,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GalleryAccessUiState(
            galleryAccessState = permissionMonitor.state.value,
            onboardingHandled = null,
            permissionRequested = null,
            permissionHistoryApplied = false,
            isPermissionRecovery = false,
        ),
    )

    fun refresh(canRequestAgain: Boolean) {
        permissionMonitor.refresh(canRequestAgain)
        permissionHistoryApplied.value = true
    }

    suspend fun markPermissionRequested() {
        onboardingRepository.markPermissionRequested()
        requestedInSession.value = true
    }

    fun onPermissionResult(
        canRequestAgain: Boolean,
        onAccessAvailable: (GalleryAccessState) -> Unit = {},
    ) {
        refresh(canRequestAgain)
        val accessState = permissionMonitor.state.value
        permissionRecovery.value = accessState is GalleryAccessState.Denied
        handledInSession.value = true
        persistHandled()
        if (accessState !is GalleryAccessState.Denied) {
            onAccessAvailable(accessState)
        }
    }

    fun dismissOnboarding() {
        handledInSession.value = true
        permissionRecovery.value = false
        persistHandled()
    }

    private fun persistHandled() {
        viewModelScope.launch {
            onboardingRepository.markHandled()
        }
    }

    companion object {
        fun factory(
            permissionMonitor: GalleryPermissionMonitor,
            onboardingRepository: GalleryOnboardingRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GalleryAccessViewModel(permissionMonitor, onboardingRepository)
            }
        }
    }
}
