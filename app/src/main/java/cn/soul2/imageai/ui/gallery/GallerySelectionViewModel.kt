package cn.soul2.imageai.ui.gallery

import androidx.lifecycle.ViewModel
import cn.soul2.imageai.gallery.GalleryImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class GallerySelectionViewModel : ViewModel() {
    private val mutableSelected = MutableStateFlow<Map<Long, GalleryImage>>(emptyMap())
    val selected = mutableSelected.asStateFlow()

    fun toggle(image: GalleryImage) {
        mutableSelected.value = mutableSelected.value.toMutableMap().apply {
            if (remove(image.localId) == null) put(image.localId, image)
        }
    }

    fun clear() { mutableSelected.value = emptyMap() }
}
