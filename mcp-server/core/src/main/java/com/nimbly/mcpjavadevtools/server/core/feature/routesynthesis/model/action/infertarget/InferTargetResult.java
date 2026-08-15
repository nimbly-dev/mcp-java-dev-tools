package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.infertarget;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisActionResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetHints;
import java.util.List;

/**
 * Typed ranked-candidate output for target inference.
 *
 * @param projectRootAbs normalized project root
 * @param hints safe echoed target hints
 * @param additionalSourceRoots normalized additional roots
 * @param scannedJavaFiles bounded scan count
 * @param candidates stable target candidates
 */
public record InferTargetResult(
        String projectRootAbs,
        RouteTargetHints hints,
        List<String> additionalSourceRoots,
        int scannedJavaFiles,
        List<RouteTargetCandidate> candidates) implements RouteSynthesisActionResult {

    /**
     * Defensively copies returned collections.
     */
    public InferTargetResult {
        additionalSourceRoots = additionalSourceRoots == null
                ? List.of()
                : List.copyOf(additionalSourceRoots);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
