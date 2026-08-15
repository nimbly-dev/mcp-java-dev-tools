package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeLineResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeCapture;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.routing.RouteSynthesisProbeRouteResolution;

/**
 * Bounded runtime and Probe evidence contract for source line resolution.
 */
@FunctionalInterface
public interface RouteSynthesisRuntimeEvidenceProvider {

    /**
     * Resolves the first runtime-valid line in a bounded source span.
     *
     * @param methodKey class and method identity
     * @param startLine first candidate line
     * @param endLine last candidate line
     * @param route resolved Probe route
     * @return bounded line evidence
     */
    RouteSynthesisRuntimeLineResolution resolveLine(
            String methodKey,
            int startLine,
            int endLine,
            RouteSynthesisProbeRouteResolution route);

    /** Reads bounded runtime capture metadata through the same public Probe boundary. */
    default RouteSynthesisRuntimeCapture capture(
            String methodKey,
            int line,
            RouteSynthesisProbeRouteResolution route) {
        return RouteSynthesisRuntimeCapture.unavailable("probe_capture_unconfigured");
    }
}
