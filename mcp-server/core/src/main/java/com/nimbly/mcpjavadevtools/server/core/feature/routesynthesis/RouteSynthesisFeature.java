package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;

/**
 * Intentional Spring-independent Core entry point for Route Synthesis.
 */
public interface RouteSynthesisFeature {

    /**
     * Executes one typed Route Synthesis request.
     *
     * @param request feature-owned request
     * @return deterministic Route Synthesis result
     */
    RouteSynthesisResult execute(RouteSynthesisRequest request);
}
