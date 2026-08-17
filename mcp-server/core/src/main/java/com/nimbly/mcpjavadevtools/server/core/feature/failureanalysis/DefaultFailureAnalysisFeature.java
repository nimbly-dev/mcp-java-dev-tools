package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis;

import com.nimbly.mcpjavadevtools.server.core.dispatch.EnumActionDispatcher;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.FailureAnalysisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.FailureAnalysisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.request.FailureAnalysisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureAnalysisResult;
import java.util.List;

/** Complete production implementation of the consolidated Failure Analysis Feature. */
public final class DefaultFailureAnalysisFeature implements FailureAnalysisFeature {

    private final EnumActionDispatcher<FailureAnalysisAction, FailureAnalysisRequest, FailureAnalysisResult> dispatcher;

    /**
     * Creates a complete action dispatcher.
     *
     * @param handlers real action implementations
     */
    public DefaultFailureAnalysisFeature(List<? extends FailureAnalysisActionHandler> handlers) {
        dispatcher = new EnumActionDispatcher<>(FailureAnalysisAction.class, handlers);
    }

    @Override
    public FailureAnalysisResult execute(FailureAnalysisRequest request) {
        if (request == null || request.action() == null) {
            return FailureAnalysisResult.invalidRequest();
        }
        return dispatcher.dispatch(request.action(), request);
    }
}
