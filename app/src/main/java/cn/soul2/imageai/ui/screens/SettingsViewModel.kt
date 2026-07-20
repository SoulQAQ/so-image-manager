package cn.soul2.imageai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.media.permission.GalleryAccessState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

enum class SettingsPermissionLabel {
    Full,
    Partial,
    Denied,
}

enum class SettingsCommand {
    ReselectPhotos,
    SelectDocumentImages,
    Rescan,
    OpenSystemSettings,
}

data class SettingsUiState(
    val permissionLabel: SettingsPermissionLabel? = null,
    val indexedCount: Int? = null,
    val unavailableCount: Int? = null,
)

class SettingsViewModel(
    galleryAccessStates: Flow<GalleryAccessState>,
    repository: GalleryRepository,
    unavailableCounts: Flow<Int>,
) : ViewModel() {
    private val commandChannel = Channel<SettingsCommand>(Channel.BUFFERED)
    val commands: Flow<SettingsCommand> = commandChannel.receiveAsFlow()

    val uiState = combine(
        galleryAccessStates,
        repository.observeCount(),
        unavailableCounts,
    ) { accessState, indexedCount, unavailableCount ->
        SettingsUiState(
            permissionLabel = when (accessState) {
                GalleryAccessState.Full -> SettingsPermissionLabel.Full
                GalleryAccessState.Partial -> SettingsPermissionLabel.Partial
                is GalleryAccessState.Denied -> SettingsPermissionLabel.Denied
            },
            indexedCount = indexedCount,
            unavailableCount = unavailableCount,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun reselectPhotos() {
        commandChannel.trySend(SettingsCommand.ReselectPhotos)
    }

    fun selectDocumentImages() {
        commandChannel.trySend(SettingsCommand.SelectDocumentImages)
    }

    fun rescan() {
        commandChannel.trySend(SettingsCommand.Rescan)
    }

    fun openSystemSettings() {
        commandChannel.trySend(SettingsCommand.OpenSystemSettings)
    }

    companion object {
        fun factory(
            galleryAccessStates: Flow<GalleryAccessState>,
            repository: GalleryRepository,
            unavailableCounts: Flow<Int>,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    galleryAccessStates = galleryAccessStates,
                    repository = repository,
                    unavailableCounts = unavailableCounts,
                )
            }
        }
    }
}
