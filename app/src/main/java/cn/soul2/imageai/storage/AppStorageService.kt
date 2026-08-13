package cn.soul2.imageai.storage

import android.content.Context
import java.io.File

data class AppStorageSnapshot(
    val databaseBytes: Long,
    val cacheBytes: Long,
    val temporaryBytes: Long,
) { val totalBytes: Long get() = databaseBytes + cacheBytes + temporaryBytes }

class AppStorageService(context: Context) {
    private val appContext = context.applicationContext
    private val temporaryDirectory = File(appContext.cacheDir, "analysis-temp")

    fun measure(): AppStorageSnapshot {
        val database = appContext.getDatabasePath("so_image_manager.db")
        return AppStorageSnapshot(
            databaseBytes = listOf(database, File(database.path + "-wal"), File(database.path + "-shm"))
                .sumOf(::safeFileSize),
            cacheBytes = safeTreeSize(appContext.cacheDir) - safeTreeSize(temporaryDirectory),
            temporaryBytes = safeTreeSize(temporaryDirectory),
        )
    }

    fun clearRebuildableFiles(): AppStorageSnapshot {
        appContext.cacheDir.listFiles()?.forEach { child ->
            if (child.canonicalFile.path.startsWith(appContext.cacheDir.canonicalFile.path)) {
                child.deleteRecursively()
            }
        }
        return measure()
    }

    private fun safeTreeSize(file: File): Long = runCatching {
        if (!file.exists()) 0L else file.walkTopDown().filter(File::isFile).sumOf(::safeFileSize)
    }.getOrDefault(0L)

    private fun safeFileSize(file: File): Long = runCatching { if (file.isFile) file.length() else 0L }
        .getOrDefault(0L)
}
