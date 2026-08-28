package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance;

import com.nimbly.mcpjavadevtools.server.core.dispatch.EnumActionDispatcher;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.action.PerformanceSuiteActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.action.PerformanceSuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.request.PerformanceSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.result.PerformanceSuiteResult;
import java.util.List;
import java.util.Map;

/** Default production Performance Suite Feature implementation. */
public final class DefaultPerformanceSuiteFeature implements PerformanceSuiteFeature {

    private final EnumActionDispatcher<PerformanceSuiteAction, PerformanceSuiteRequest, PerformanceSuiteResult> dispatcher;

    /** Creates the Feature with its complete action-handler set. */
    public DefaultPerformanceSuiteFeature(List<? extends PerformanceSuiteActionHandler> handlers) {
        dispatcher = new EnumActionDispatcher<>(PerformanceSuiteAction.class, handlers);
    }

    @Override
    public PerformanceSuiteResult execute(PerformanceSuiteRequest request) {
        if (request == null || request.action() == null) {
            return PerformanceSuiteResult.blocked(
                    "performance_suite_request_invalid",
                    "a Performance Suite action is required",
                    Map.of("failedStep", "input_validation"));
        }
        return dispatcher.dispatch(request.action(), request);
    }
}
