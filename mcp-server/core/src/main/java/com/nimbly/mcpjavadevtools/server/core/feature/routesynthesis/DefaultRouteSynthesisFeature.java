package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis;

import com.nimbly.mcpjavadevtools.server.core.dispatch.EnumActionDispatcher;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.RouteSynthesisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import java.util.List;

/**
 * Complete dispatcher-backed Route Synthesis Feature implementation.
 */
public class DefaultRouteSynthesisFeature implements RouteSynthesisFeature {

    private final EnumActionDispatcher<RouteSynthesisAction, RouteSynthesisRequest, RouteSynthesisResult>
            dispatcher;

    /**
     * Creates a Feature that rejects incomplete action wiring.
     *
     * @param handlers complete action handler set
     */
    public DefaultRouteSynthesisFeature(List<? extends RouteSynthesisActionHandler> handlers) {
        dispatcher = new EnumActionDispatcher<>(RouteSynthesisAction.class, handlers);
    }

    /**
     * Dispatches one action without owning action behavior.
     *
     * @param request typed Route Synthesis request
     * @return deterministic action result
     */
    @Override
    public RouteSynthesisResult execute(RouteSynthesisRequest request) {
        if (request == null || request.action() == null) {
            return RouteSynthesisResult.report(
                    "blocked_invalid",
                    "invalid_request",
                    "input_validation",
                    "invalid_request",
                    "Provide a valid route_synthesis action and rerun.");
        }
        return dispatcher.dispatch(request.action(), request);
    }
}
