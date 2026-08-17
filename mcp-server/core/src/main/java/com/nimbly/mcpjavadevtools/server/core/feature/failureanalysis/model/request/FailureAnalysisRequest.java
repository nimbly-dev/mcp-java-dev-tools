package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.request;

import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.FailureAnalysisAction;

/** Sealed-by-convention request contract used by the Core action dispatcher. */
public interface FailureAnalysisRequest {

    /** @return selected public action */
    FailureAnalysisAction action();
}
