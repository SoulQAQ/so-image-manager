package cn.soul2.imageai.analysis

fun interface SearchProjectionWriter {
    suspend fun replaceForImage(imageLocalId: Long, projection: EffectiveProjectionSnapshot)

    companion object {
        val NoOp = SearchProjectionWriter { _, _ -> }
    }
}
