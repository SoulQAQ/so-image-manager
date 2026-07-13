package cn.soul2.imageai.media.sync

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MediaStoreChangeDebouncer(
    private val scope: CoroutineScope,
    private val delayMillis: Long,
    private val onDebouncedChange: () -> Unit,
) {
    private var pending: Job? = null

    fun onChange() {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMillis)
            onDebouncedChange()
        }
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }
}

class MediaStoreObserver(
    context: Context,
    scheduler: MediaSyncScheduler,
    scope: CoroutineScope,
) {
    private val contentResolver = context.applicationContext.contentResolver
    private val debouncer = MediaStoreChangeDebouncer(
        scope = scope,
        delayMillis = SyncPolicy.OBSERVER_DEBOUNCE_MILLIS,
        onDebouncedChange = scheduler::requestIncremental,
    )
    private val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            debouncer.onChange()
        }
    }
    private var registered = false

    fun register() {
        if (registered) return
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver,
        )
        registered = true
    }

    fun unregister() {
        if (!registered) return
        contentResolver.unregisterContentObserver(contentObserver)
        registered = false
        debouncer.cancel()
    }
}
