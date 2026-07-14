package cn.soul2.imageai.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GalleryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface ImageDetailUiState {
    data object Loading : ImageDetailUiState
    data object Missing : ImageDetailUiState
    data class Ready(val image: GalleryImage) : ImageDetailUiState
}

class ImageDetailViewModel(
    repository: GalleryRepository,
    localId: Long,
) : ViewModel() {
    val uiState = repository.observeImage(localId)
        .map { image -> image?.let(ImageDetailUiState::Ready) ?: ImageDetailUiState.Missing }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ImageDetailUiState.Loading,
        )

    companion object {
        fun factory(
            repository: GalleryRepository,
            localId: Long,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ImageDetailViewModel(repository, localId) }
        }
    }
}
