package cn.soul2.imageai.media.store

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import cn.soul2.imageai.media.sync.SyncMode

class AndroidMediaStoreGateway(
    context: Context,
    private val contentResolver: ContentResolver = context.contentResolver,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : MediaStoreGateway {
    private val applicationContext = context.applicationContext

    override fun externalVolumes(): Set<String> =
        MediaStore.getExternalVolumeNames(applicationContext)

    override fun readPage(
        volume: String,
        mode: SyncMode,
        cursor: MediaStoreCursor?,
        limit: Int,
    ): MediaStorePage {
        val plan = MediaStoreQueryPlan.create(mode, cursor, sdkInt, limit)
        val observedGeneration = if (sdkInt >= Build.VERSION_CODES.R) {
            MediaStore.getGeneration(applicationContext, volume)
        } else {
            null
        }
        val observedVersion = if (sdkInt >= Build.VERSION_CODES.R) {
            MediaStore.getVersion(applicationContext, volume)
        } else {
            MediaStore.getVersion(applicationContext)
        }
        val collectionUri = MediaStore.Images.Media.getContentUri(volume)
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, plan.selection)
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                plan.selectionArgs.toTypedArray(),
            )
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, plan.sortOrder)
            putInt(ContentResolver.QUERY_ARG_LIMIT, plan.limit)
        }
        val images = contentResolver.query(
            collectionUri,
            plan.projection.toTypedArray(),
            queryArgs,
            null,
        )?.use { result ->
            buildList {
                while (result.moveToNext()) {
                    add(result.toMediaStoreImage(volume))
                }
            }
        }.orEmpty()
        val nextCursor = images.lastOrNull()?.toCursor(mode, sdkInt) ?: cursor
        return MediaStorePage(
            images = images,
            nextCursor = nextCursor,
            hasMore = images.size == limit,
            observedGeneration = observedGeneration,
            observedVersion = observedVersion,
        )
    }

    private fun Cursor.toMediaStoreImage(fallbackVolume: String): MediaStoreImage {
        val volumeName = nullableString(COLUMN_VOLUME_NAME) ?: fallbackVolume
        val mediaStoreId = getLong(getColumnIndexOrThrow(COLUMN_ID))
        val collectionUri = MediaStore.Images.Media.getContentUri(volumeName)
        return MediaStoreImage(
            volumeName = volumeName,
            mediaStoreId = mediaStoreId,
            contentUri = ContentUris.withAppendedId(collectionUri, mediaStoreId).toString(),
            displayName = nullableString(COLUMN_DISPLAY_NAME).orEmpty(),
            mimeType = nullableString(COLUMN_MIME_TYPE) ?: DEFAULT_MIME_TYPE,
            width = nullableInt(COLUMN_WIDTH) ?: 0,
            height = nullableInt(COLUMN_HEIGHT) ?: 0,
            sizeBytes = nullableLong(COLUMN_SIZE) ?: 0L,
            capturedAtEpochMillis = nullableLong(COLUMN_DATE_TAKEN),
            addedAtEpochMillis = MediaStoreTime.secondsToEpochMillis(
                nullableLong(COLUMN_DATE_ADDED) ?: 0L,
            ),
            modifiedAtEpochMillis = MediaStoreTime.secondsToEpochMillis(
                nullableLong(COLUMN_DATE_MODIFIED) ?: 0L,
            ),
            bucketId = nullableLong(COLUMN_BUCKET_ID),
            bucketName = nullableString(COLUMN_BUCKET_NAME),
            isFavorite = nullableInt(COLUMN_IS_FAVORITE) == 1,
            generationModified = nullableLong(COLUMN_GENERATION_MODIFIED),
        )
    }

    private fun Cursor.nullableString(columnName: String): String? =
        columnIndex(columnName)?.let { index -> if (isNull(index)) null else getString(index) }

    private fun Cursor.nullableInt(columnName: String): Int? =
        columnIndex(columnName)?.let { index -> if (isNull(index)) null else getInt(index) }

    private fun Cursor.nullableLong(columnName: String): Long? =
        columnIndex(columnName)?.let { index -> if (isNull(index)) null else getLong(index) }

    private fun Cursor.columnIndex(columnName: String): Int? =
        getColumnIndex(columnName).takeIf { it >= 0 }

    private companion object {
        const val DEFAULT_MIME_TYPE = "image/*"
    }
}

internal data class MediaStoreQueryPlan(
    val projection: List<String>,
    val selection: String,
    val selectionArgs: List<String>,
    val sortOrder: String,
    val limit: Int,
) {
    companion object {
        fun create(
            mode: SyncMode,
            cursor: MediaStoreCursor?,
            sdkInt: Int,
            limit: Int,
        ): MediaStoreQueryPlan {
            require(limit > 0) { "limit must be positive" }
            val projection = buildList {
                addAll(BASE_PROJECTION)
                if (sdkInt >= Build.VERSION_CODES.R) {
                    add(COLUMN_IS_FAVORITE)
                    add(COLUMN_GENERATION_MODIFIED)
                }
            }
            val predicates = mutableListOf("$COLUMN_IS_PENDING = ?")
            val arguments = mutableListOf("0")
            if (sdkInt >= Build.VERSION_CODES.R) {
                predicates += "$COLUMN_IS_TRASHED = ?"
                arguments += "0"
            }
            val sortOrder = when {
                mode == SyncMode.INCREMENTAL && sdkInt >= Build.VERSION_CODES.R -> {
                    cursor?.generation?.let { generation ->
                        predicates +=
                            "($COLUMN_GENERATION_MODIFIED > ? OR " +
                            "($COLUMN_GENERATION_MODIFIED = ? AND $COLUMN_ID > ?))"
                        arguments += listOf(
                            generation.toString(),
                            generation.toString(),
                            cursor.mediaStoreId.toString(),
                        )
                    }
                    "$COLUMN_GENERATION_MODIFIED ASC, $COLUMN_ID ASC"
                }
                mode == SyncMode.INCREMENTAL -> {
                    cursor?.modifiedAtEpochMillis?.let { modifiedAtEpochMillis ->
                        val seconds = modifiedAtEpochMillis / MILLIS_PER_SECOND
                        predicates +=
                            "($COLUMN_DATE_MODIFIED > ? OR " +
                            "($COLUMN_DATE_MODIFIED = ? AND $COLUMN_ID > ?))"
                        arguments += listOf(
                            seconds.toString(),
                            seconds.toString(),
                            cursor.mediaStoreId.toString(),
                        )
                    }
                    "$COLUMN_DATE_MODIFIED ASC, $COLUMN_ID ASC"
                }
                else -> {
                    cursor?.modifiedAtEpochMillis?.let { modifiedAtEpochMillis ->
                        val seconds = modifiedAtEpochMillis / MILLIS_PER_SECOND
                        predicates +=
                            "($COLUMN_DATE_MODIFIED < ? OR " +
                            "($COLUMN_DATE_MODIFIED = ? AND $COLUMN_ID < ?))"
                        arguments += listOf(
                            seconds.toString(),
                            seconds.toString(),
                            cursor.mediaStoreId.toString(),
                        )
                    }
                    "$COLUMN_DATE_MODIFIED DESC, $COLUMN_ID DESC"
                }
            }
            return MediaStoreQueryPlan(
                projection = projection,
                selection = predicates.joinToString(separator = " AND "),
                selectionArgs = arguments,
                sortOrder = sortOrder,
                limit = limit,
            )
        }
    }
}

private fun MediaStoreImage.toCursor(mode: SyncMode, sdkInt: Int): MediaStoreCursor =
    when {
        mode == SyncMode.INCREMENTAL && sdkInt >= Build.VERSION_CODES.R -> MediaStoreCursor(
            modifiedAtEpochMillis = null,
            mediaStoreId = mediaStoreId,
            generation = generationModified,
        )
        else -> MediaStoreCursor(
            modifiedAtEpochMillis = modifiedAtEpochMillis,
            mediaStoreId = mediaStoreId,
            generation = null,
        )
    }

private const val MILLIS_PER_SECOND = 1_000L
private const val COLUMN_ID = "_id"
private const val COLUMN_VOLUME_NAME = "volume_name"
private const val COLUMN_DISPLAY_NAME = "_display_name"
private const val COLUMN_MIME_TYPE = "mime_type"
private const val COLUMN_WIDTH = "width"
private const val COLUMN_HEIGHT = "height"
private const val COLUMN_SIZE = "_size"
private const val COLUMN_DATE_TAKEN = "datetaken"
private const val COLUMN_DATE_ADDED = "date_added"
private const val COLUMN_DATE_MODIFIED = "date_modified"
private const val COLUMN_BUCKET_ID = "bucket_id"
private const val COLUMN_BUCKET_NAME = "bucket_display_name"
private const val COLUMN_IS_FAVORITE = "is_favorite"
private const val COLUMN_GENERATION_MODIFIED = "generation_modified"
private const val COLUMN_IS_PENDING = "is_pending"
private const val COLUMN_IS_TRASHED = "is_trashed"

private val BASE_PROJECTION = listOf(
    COLUMN_ID,
    COLUMN_VOLUME_NAME,
    COLUMN_DISPLAY_NAME,
    COLUMN_MIME_TYPE,
    COLUMN_WIDTH,
    COLUMN_HEIGHT,
    COLUMN_SIZE,
    COLUMN_DATE_TAKEN,
    COLUMN_DATE_ADDED,
    COLUMN_DATE_MODIFIED,
    COLUMN_BUCKET_ID,
    COLUMN_BUCKET_NAME,
)
