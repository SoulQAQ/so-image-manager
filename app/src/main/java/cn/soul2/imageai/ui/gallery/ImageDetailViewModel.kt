package cn.soul2.imageai.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.soul2.imageai.ai.analysis.ImageAnalysisTarget
import cn.soul2.imageai.ai.analysis.SingleImageAnalysisFailure
import cn.soul2.imageai.ai.analysis.SingleImageAnalysisResult
import cn.soul2.imageai.ai.analysis.SingleImageAnalyzer
import cn.soul2.imageai.analysis.CanonicalMetadataRepository
import cn.soul2.imageai.analysis.CorrectionCommand
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GalleryImageWindow
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.gallery.GallerySource
import cn.soul2.imageai.data.db.entity.ImagePartition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ImageDetailUiState {
    data object Loading : ImageDetailUiState
    data object Missing : ImageDetailUiState
    data class Ready(val window: GalleryImageWindow) : ImageDetailUiState {
        val image: GalleryImage get() = window.current
    }
}

sealed interface ImageAnalysisUiState {
    data object Idle : ImageAnalysisUiState
    data class Running(val imageLocalId: Long) : ImageAnalysisUiState
    data class Success(val imageLocalId: Long) : ImageAnalysisUiState
    data class Failure(
        val imageLocalId: Long,
        val reason: SingleImageAnalysisFailure,
    ) : ImageAnalysisUiState
}

sealed interface ImageCorrectionUiState {
    data object Idle : ImageCorrectionUiState
    data object Saving : ImageCorrectionUiState
    data object Failed : ImageCorrectionUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class ImageDetailViewModel(
    private val repository: GalleryRepository,
    localId: Long,
    private val singleImageAnalyzer: SingleImageAnalyzer? = null,
    private val metadataRepository: CanonicalMetadataRepository? = null,
    private val source: GallerySource = GallerySource.All,
) : ViewModel() {
    private val selectedLocalId = MutableStateFlow(localId)

    val uiState = selectedLocalId
        .flatMapLatest { localId ->
            if (source.isPrivateSource()) repository.observeImageWindow(localId, source)
            else repository.observeImageWindow(localId)
        }
        .map { window -> window?.let(ImageDetailUiState::Ready) ?: ImageDetailUiState.Missing }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ImageDetailUiState.Loading,
        )

    val effectiveMetadata = selectedLocalId
        .flatMapLatest(repository::observeEffectiveMetadata)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    private val mutableAnalysisState = MutableStateFlow<ImageAnalysisUiState>(
        ImageAnalysisUiState.Idle,
    )
    val analysisState = mutableAnalysisState.asStateFlow()
    private val mutableCorrectionState = MutableStateFlow<ImageCorrectionUiState>(
        ImageCorrectionUiState.Idle,
    )
    val correctionState = mutableCorrectionState.asStateFlow()

    fun showImage(localId: Long) {
        require(localId > 0L) { "localId must be positive" }
        selectedLocalId.value = localId
    }

    fun analyzeCurrentImage() {
        val analyzer = singleImageAnalyzer ?: run {
            mutableAnalysisState.value = ImageAnalysisUiState.Failure(
                selectedLocalId.value,
                SingleImageAnalysisFailure.INTERNAL_ERROR,
            )
            return
        }
        if (mutableAnalysisState.value is ImageAnalysisUiState.Running) return
        val targetLocalId = selectedLocalId.value
        mutableAnalysisState.value = ImageAnalysisUiState.Running(targetLocalId)
        viewModelScope.launch {
            val image = if (source.isPrivateSource()) {
                repository.observeImage(targetLocalId, source).first()
            } else {
                repository.observeImage(targetLocalId).first()
            }
            if (image == null) {
                mutableAnalysisState.value = ImageAnalysisUiState.Failure(
                    targetLocalId,
                    SingleImageAnalysisFailure.IMAGE_UNAVAILABLE,
                )
                return@launch
            }
            mutableAnalysisState.value = when (
                val result = analyzer.analyze(
                    ImageAnalysisTarget(targetLocalId, image.contentUri, source.analysisPartition()),
                )
            ) {
                is SingleImageAnalysisResult.Success -> ImageAnalysisUiState.Success(targetLocalId)
                is SingleImageAnalysisResult.Failure -> ImageAnalysisUiState.Failure(
                    targetLocalId,
                    result.reason,
                )
            }
        }
    }

    fun applyCorrection(command: CorrectionCommand) {
        val corrections = metadataRepository ?: run {
            mutableCorrectionState.value = ImageCorrectionUiState.Failed
            return
        }
        if (mutableCorrectionState.value is ImageCorrectionUiState.Saving) return
        val imageLocalId = selectedLocalId.value
        mutableCorrectionState.value = ImageCorrectionUiState.Saving
        viewModelScope.launch {
            mutableCorrectionState.value = try {
                corrections.applyCorrection(imageLocalId, command, System.currentTimeMillis())
                ImageCorrectionUiState.Idle
            } catch (_: IllegalArgumentException) {
                ImageCorrectionUiState.Failed
            } catch (_: IllegalStateException) {
                ImageCorrectionUiState.Failed
            }
        }
    }

    companion object {
        fun factory(
            repository: GalleryRepository,
            localId: Long,
            singleImageAnalyzer: SingleImageAnalyzer? = null,
            metadataRepository: CanonicalMetadataRepository? = null,
            source: GallerySource = GallerySource.All,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ImageDetailViewModel(
                    repository,
                    localId,
                    singleImageAnalyzer,
                    metadataRepository,
                    source,
                )
            }
        }
    }
}

private fun GallerySource.analysisPartition(): ImagePartition = when (this) {
    GallerySource.Private,
    GallerySource.PrivateUnanalyzable,
    GallerySource.Rejected,
    -> ImagePartition.PRIVATE
    GallerySource.Unanalyzed -> ImagePartition.UNPROCESSED
    else -> ImagePartition.MAIN
}

private fun GallerySource.isPrivateSource(): Boolean = this in setOf(
    GallerySource.Private,
    GallerySource.PrivateUnanalyzable,
    GallerySource.Rejected,
)
