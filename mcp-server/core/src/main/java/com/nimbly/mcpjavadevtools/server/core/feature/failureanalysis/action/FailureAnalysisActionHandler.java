package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action;

import com.nimbly.mcpjavadevtools.server.core.dispatch.ActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.FailureAnalysisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.request.FailureAnalysisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureAnalysisResult;

/** Typed specialization of the shared action-handler contract. */
public interface FailureAnalysisActionHandler
        extends ActionHandler<FailureAnalysisAction, FailureAnalysisRequest, FailureAnalysisResult> {
}
