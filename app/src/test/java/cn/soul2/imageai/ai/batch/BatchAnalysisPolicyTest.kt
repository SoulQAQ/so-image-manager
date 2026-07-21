package cn.soul2.imageai.ai.batch

import cn.soul2.imageai.ai.analysis.SingleImageAnalysisFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchAnalysisPolicyTest {
    @Test
    fun pausesForSystemicConfigurationAndQuotaFailures() {
        assertTrue(BatchAnalysisPolicy.pausesRun(SingleImageAnalysisFailure.CONFIGURATION_REQUIRED))
        assertTrue(BatchAnalysisPolicy.pausesRun(SingleImageAnalysisFailure.REQUEST_LIMITED))
        assertTrue(BatchAnalysisPolicy.pausesRun(SingleImageAnalysisFailure.CREDENTIAL_REQUIRED))
    }

    @Test
    fun continuesAfterImageSpecificOrProviderFailures() {
        assertFalse(BatchAnalysisPolicy.pausesRun(SingleImageAnalysisFailure.IMAGE_UNAVAILABLE))
        assertFalse(BatchAnalysisPolicy.pausesRun(SingleImageAnalysisFailure.NETWORK_FAILED))
        assertFalse(BatchAnalysisPolicy.pausesRun(SingleImageAnalysisFailure.PROVIDER_REJECTED))
    }
}
