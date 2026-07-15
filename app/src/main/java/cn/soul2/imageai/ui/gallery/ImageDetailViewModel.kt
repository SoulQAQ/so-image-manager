package cn.soul2.imageai.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GalleryImageWindow
import cn.soul2.imageai.gallery.GalleryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi

sealed interface ImageDetailUiState {
    data object Loading : ImageDetailUiState
    data object Missing : ImageDetailUiState
    data class Ready(val window: GalleryImageWindow) : ImageDetailUiState {
        val image: GalleryImage get() = window.current
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ImageDetailViewModel(
    repository: GalleryRepository,
    localId: Long,
) : ViewModel() {
    private val selectedLocalId = MutableStateFlow(localId)

    val uiState = selectedLocalId
        .flatMapLatest(repository::observeImageWindow)
        .map { window -> window?.let(ImageDetailUiState::Ready) ?: ImageDetailUiState.Missing }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ImageDetailUiState.Loading,
        )

    fun showImage(localId: Long) {
        require(localId > 0L) { "localId must be positive" }
        selectedLocalId.value = localId
    }

    companion object {
        fun factory(
            repository: GalleryRepository,
            localId: Long,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ImageDetailViewModel(repository, localId) }
        }
    }
}
