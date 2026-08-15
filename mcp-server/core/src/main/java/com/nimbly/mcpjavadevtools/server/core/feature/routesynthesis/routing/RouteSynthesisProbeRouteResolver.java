package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.routing;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.routing.RouteSynthesisProbeRouteResolution;

/**
 * Deterministic Probe target and base-route resolution contract.
 */
@FunctionalInterface
public interface RouteSynthesisProbeRouteResolver {

    /**
     * Resolves a Probe route using typed Application-provided configuration.
     *
     * @param probeId optional Probe registry identifier
     * @param probeBaseUrl optional explicit base URL
     * @return deterministic route resolution
     */
    RouteSynthesisProbeRouteResolution resolve(String probeId, String probeBaseUrl);
}
