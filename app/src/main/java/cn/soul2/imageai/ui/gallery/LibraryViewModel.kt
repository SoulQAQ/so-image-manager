package cn.soul2.imageai.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.cachedIn
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import cn.soul2.imageai.gallery.GalleryQuery
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.gallery.GallerySource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class LibraryViewModel(
    repository: GalleryRepository,
    syncRuns: Flow<MediaSyncRunEntity?>,
) : ViewModel() {
    val images = repository.observe(GalleryQuery(GallerySource.All)).cachedIn(viewModelScope)

    val uiState = combine(repository.observeCount(), syncRuns) { count, syncRun ->
        GalleryUiState(count, syncRun)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GalleryUiState(availableCount = null, syncRun = null),
    )

    companion object {
        fun factory(
            repository: GalleryRepository,
            syncRuns: Flow<MediaSyncRunEntity?>,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { LibraryViewModel(repository, syncRuns) }
        }
    }
}
