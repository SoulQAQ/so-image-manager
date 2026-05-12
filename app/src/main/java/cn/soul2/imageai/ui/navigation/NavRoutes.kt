package cn.soul2.imageai.ui.navigation

object NavRoutes {
    const val GALLERY = "gallery"
    const val IMAGE_DETAIL = "imageDetail/{index}"
    const val SETTINGS = "settings"
    const val WEBVIEW = "webview"

    fun imageDetail(index: Int): String = "imageDetail/$index"
}
