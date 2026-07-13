package cn.soul2.imageai.media.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaStoreObserverTest {
    @Test
    fun changesUseATwoSecondTrailingDebounce() = runTest {
        var requests = 0
        val debouncer = MediaStoreChangeDebouncer(
            scope = this,
            delayMillis = SyncPolicy.OBSERVER_DEBOUNCE_MILLIS,
            onDebouncedChange = { requests++ },
        )

        debouncer.onChange()
        advanceTimeBy(1_000L)
        debouncer.onChange()
        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(0, requests)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, requests)
    }
}
