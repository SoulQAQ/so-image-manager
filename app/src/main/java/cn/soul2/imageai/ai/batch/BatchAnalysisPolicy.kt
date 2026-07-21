package cn.soul2.imageai.ai.batch

import cn.soul2.imageai.ai.analysis.SingleImageAnalysisFailure

object BatchAnalysisPolicy {
    fun pausesRun(failure: SingleImageAnalysisFailure): Boolean = failure in setOf(
        SingleImageAnalysisFailure.CONFIGURATION_REQUIRED,
        SingleImageAnalysisFailure.PROTOCOL_UNSUPPORTED,
        SingleImageAnalysisFailure.CREDENTIAL_REQUIRED,
        SingleImageAnalysisFailure.CREDENTIAL_UNAVAILABLE,
        SingleImageAnalysisFailure.REQUEST_LIMITED,
    )
}
