package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;

/**
 * Common typed request contract for Route Synthesis actions.
 */
public interface RouteSynthesisRequest {

    /**
     * Returns the selected public action.
     *
     * @return selected action
     */
    RouteSynthesisAction action();
}
