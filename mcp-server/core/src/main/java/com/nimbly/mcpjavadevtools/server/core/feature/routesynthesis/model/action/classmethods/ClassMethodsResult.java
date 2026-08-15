package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.classmethods;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisActionResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetClass;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetHints;
import java.util.List;

/**
 * Typed class method inventory output.
 *
 * @param projectRootAbs normalized project root
 * @param hints safe echoed target hints
 * @param additionalSourceRoots normalized additional roots
 * @param scannedJavaFiles bounded scan count
 * @param target selected class
 */
public record ClassMethodsResult(
        String projectRootAbs,
        RouteTargetHints hints,
        List<String> additionalSourceRoots,
        int scannedJavaFiles,
        RouteTargetClass target) implements RouteSynthesisActionResult {

    /**
     * Defensively copies returned collections.
     */
    public ClassMethodsResult {
        additionalSourceRoots = additionalSourceRoots == null
                ? List.of()
                : List.copyOf(additionalSourceRoots);
    }
}
