package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis;

import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.request.FailureAnalysisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureAnalysisResult;

/** Spring-independent public entry point for the consolidated Failure Analysis Feature. */
public interface FailureAnalysisFeature {

    /**
     * Executes one typed Failure Analysis action.
     *
     * @param request feature-owned request
     * @return deterministic Feature result
     */
    FailureAnalysisResult execute(FailureAnalysisRequest request);
}
