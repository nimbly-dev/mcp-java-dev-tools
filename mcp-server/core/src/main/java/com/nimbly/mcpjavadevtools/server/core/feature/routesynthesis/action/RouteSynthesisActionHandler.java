package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action;

import com.nimbly.mcpjavadevtools.server.core.dispatch.ActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;

/**
 * Route Synthesis specialization of the capability-neutral action contract.
 */
public interface RouteSynthesisActionHandler
        extends ActionHandler<RouteSynthesisAction, RouteSynthesisRequest, RouteSynthesisResult> {
}
