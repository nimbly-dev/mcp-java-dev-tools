package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis;

import com.nimbly.mcpjavadevtools.server.core.dispatch.EnumActionDispatcher;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.FailureAnalysisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.FailureAnalysisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.request.FailureAnalysisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureAnalysisResult;
import java.util.List;

/** COMPATIBILITY_RETAINED_UNTIL_611: used by FailureAnalysisMcpTool; delete with that #611 adapter. */
public final class DefaultFailureAnalysisFeature implements FailureAnalysisFeature {

    private final List<? extends FailureAnalysisActionHandler> handlers;

    /**
     * Creates a complete action dispatcher.
     *
     * @param handlers real action implementations
     */
    public DefaultFailureAnalysisFeature(List<? extends FailureAnalysisActionHandler> handlers) {
        this.handlers = List.copyOf(handlers);
        new EnumActionDispatcher<>(FailureAnalysisAction.class, this.handlers);
    }

    /** @return substantive owner for an action registered with the Core operation directory */
    public FailureAnalysisActionHandler operationOwner(FailureAnalysisAction action) {
        return handlers.stream().filter(handler -> handler.action() == action).findFirst().orElseThrow();
    }

    @Override
    public FailureAnalysisResult execute(FailureAnalysisRequest request) {
        if (request == null || request.action() == null) {
            return FailureAnalysisResult.invalidRequest();
        }
        return operationOwner(request.action()).execute(request);
    }
}
