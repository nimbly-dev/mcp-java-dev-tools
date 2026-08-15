package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.classmethods;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisActionResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetClassMatch;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetHints;
import java.util.List;

/**
 * Candidate classes returned when class_methods cannot select one class.
 *
 * @param projectRootAbs normalized project root
 * @param hints safe echoed target hints
 * @param additionalSourceRoots normalized additional roots
 * @param scannedJavaFiles bounded scan count
 * @param matches stable candidate class summaries
 */
public record ClassMethodsMatchesResult(
        String projectRootAbs,
        RouteTargetHints hints,
        List<String> additionalSourceRoots,
        int scannedJavaFiles,
        List<RouteTargetClassMatch> matches) implements RouteSynthesisActionResult {

    /**
     * Defensively copies returned collections.
     */
    public ClassMethodsMatchesResult {
        additionalSourceRoots = additionalSourceRoots == null
                ? List.of()
                : List.copyOf(additionalSourceRoots);
        matches = matches == null ? List.of() : List.copyOf(matches);
    }
}
