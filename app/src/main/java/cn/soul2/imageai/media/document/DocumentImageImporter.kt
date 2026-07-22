package cn.soul2.imageai.media.document

import android.content.ContentResolver
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import cn.soul2.imageai.data.db.dao.ImageDao
import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.ImagePartition
import cn.soul2.imageai.data.db.entity.ImageSource
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DocumentImageImportResult(
    val importedCount: Int,
    val rejectedCount: Int,
)

class DocumentImageImporter(
    private val contentResolver: ContentResolver,
    private val imageDao: ImageDao,
    private val afterCommit: suspend (List<ImageEntity>) -> Unit,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun import(uris: Collection<Uri>): DocumentImageImportResult = withContext(Dispatchers.IO) {
        val imported = buildList {
            uris.distinct().forEach { uri ->
                if (persistReadPermission(uri)) readImage(uri)?.let(::add)
            }
        }
        val resolved = imageDao.upsertAndResolve(imported)
        if (resolved.isNotEmpty()) afterCommit(resolved)
        DocumentImageImportResult(
            importedCount = resolved.size,
            rejectedCount = uris.distinct().size - imported.size,
        )
    }

    private fun persistReadPermission(uri: Uri): Boolean {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The persisted permission list below is the authoritative result.
        }
        return contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        }
    }

    private fun readImage(uri: Uri): ImageEntity? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val mimeType = contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: return null
        val now = nowEpochMillis()
        val metadata = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val displayName = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME) ?: "导入图片"
            val sizeBytes = cursor.longOrNull(OpenableColumns.SIZE) ?: 0L
            displayName to sizeBytes
        } ?: return null
        val dimensions = BitmapFactory.Options().also { options ->
            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        }
        val identity = DocumentImageIdentity.of(uri)
        return ImageEntity(
            volumeName = "document:${uri.authority ?: "unknown"}",
            mediaStoreId = identity.mediaStoreId,
            contentUri = uri.toString(),
            displayName = metadata.first,
            mimeType = mimeType,
            width = dimensions.outWidth.coerceAtLeast(1),
            height = dimensions.outHeight.coerceAtLeast(1),
            sizeBytes = metadata.second.coerceAtLeast(0L),
            capturedAtEpochMillis = null,
            addedAtEpochMillis = now,
            modifiedAtEpochMillis = now,
            sortTimeEpochMillis = now,
            bucketId = null,
            bucketName = "文件导入",
            isFavorite = false,
            quickFingerprint = identity.fingerprint,
            availability = ImageAvailability.AVAILABLE,
            partition = ImagePartition.UNPROCESSED,
            lastSeenSyncRunId = null,
            missingCandidateSinceEpochMillis = null,
            missingObservationCount = 0,
            source = ImageSource.DOCUMENT,
        )
    }
}

internal data class DocumentImageIdentity(
    val mediaStoreId: Long,
    val fingerprint: String,
) {
    companion object {
        fun of(uri: Uri): DocumentImageIdentity = of(uri.toString())

        fun of(uriText: String): DocumentImageIdentity {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(uriText.toByteArray(Charsets.UTF_8))
            val mediaStoreId = (ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long and Long.MAX_VALUE)
                .coerceAtLeast(1L)
            return DocumentImageIdentity(
                mediaStoreId = mediaStoreId,
                fingerprint = "document.sha256." + digest.joinToString("") { "%02x".format(it) },
            )
        }
    }
}

private fun android.database.Cursor.stringOrNull(column: String): String? =
    getColumnIndex(column).takeIf { it >= 0 }?.let { index ->
        getString(index)
    }

private fun android.database.Cursor.longOrNull(column: String): Long? =
    getColumnIndex(column).takeIf { it >= 0 }?.let { index ->
        if (isNull(index)) null else getLong(index)
    }
