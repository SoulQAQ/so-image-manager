package cn.soul2.imageai.analysis

sealed interface SearchProjectionPreparation {
    interface Ready : SearchProjectionPreparation
    data class Blocked(val code: String, val detail: String) : SearchProjectionPreparation
}

interface SearchProjectionWriter {
    fun prepareForImage(
        imageLocalId: Long,
        snapshot: EffectiveProjectionSnapshot,
    ): SearchProjectionPreparation

    fun replaceForImage(preparation: SearchProjectionPreparation.Ready)

    companion object {
        private data object NoOpReady : SearchProjectionPreparation.Ready

        val NoOp = object : SearchProjectionWriter {
            override fun prepareForImage(
                imageLocalId: Long,
                snapshot: EffectiveProjectionSnapshot,
            ): SearchProjectionPreparation = NoOpReady

            override fun replaceForImage(preparation: SearchProjectionPreparation.Ready) {
                require(preparation === NoOpReady) { "preparation belongs to another writer" }
            }
        }
    }
}
