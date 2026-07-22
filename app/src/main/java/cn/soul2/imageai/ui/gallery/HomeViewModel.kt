package cn.soul2.imageai.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.cachedIn
import androidx.paging.PagingData
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import cn.soul2.imageai.data.db.entity.AiRuntimeSettingEntity
import cn.soul2.imageai.gallery.GalleryQuery
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.gallery.GallerySource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModel(
    repository: GalleryRepository,
    syncRuns: Flow<MediaSyncRunEntity?>,
    runtimeSettings: Flow<AiRuntimeSettingEntity?> = flowOf(null),
) : ViewModel() {
    val images = runtimeSettings
        .flatMapLatest { runtime ->
            repository.observe(
                GalleryQuery(
                    if (runtime?.onlyShowAnalyzed == true) {
                        GallerySource.Analyzed
                    } else {
                        GallerySource.Recent
                    },
                ),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = PagingData.empty(),
        )
        .cachedIn(viewModelScope)

    val uiState = combine(repository.observeCount(), syncRuns) { count, syncRun ->
        GalleryUiState(count, syncRun)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = GalleryUiState(availableCount = null, syncRun = null),
    )

    companion object {
        fun factory(
            repository: GalleryRepository,
            syncRuns: Flow<MediaSyncRunEntity?>,
            runtimeSettings: Flow<AiRuntimeSettingEntity?> = flowOf(null),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(repository, syncRuns, runtimeSettings) }
        }
    }
}
