package cn.soul2.imageai.ai.quota

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AiTokenUsageLedgerTest {
    private lateinit var ledger: AiTokenUsageLedger

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("soim_ai_token_usage", 0).edit().clear().commit()
        ledger = AiTokenUsageLedger(context)
    }

    @Test
    fun accumulatesKnownUsageAndResetsAtUtcDayBoundary() {
        assertEquals(120L, ledger.record(120L, 1_000L).tokens)
        assertEquals(200L, ledger.record(80L, 2_000L).tokens)
        assertTrue(ledger.isLimitReached(200L, 2_000L))

        val nextDay = ledger.current(86_400_001L)
        assertEquals(0L, nextDay.tokens)
        assertFalse(ledger.isLimitReached(1L, 86_400_001L))
    }

    @Test
    fun marksUsageUnknownWhenProviderOmitsTokenCount() {
        val usage = ledger.record(null, 1_000L)

        assertFalse(usage.known)
        assertEquals(0L, usage.tokens)
    }
}
