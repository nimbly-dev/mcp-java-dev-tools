package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisActionResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetHints;
import java.util.List;

/**
 * Deterministic Spring HTTP handler inventory.
 *
 * @param projectRootAbs normalized project root
 * @param hints safe echoed target hints
 * @param additionalSourceRoots normalized additional roots
 * @param scannedJavaFiles bounded scan count
 * @param framework framework identifier
 * @param controllerFqcn selected controller FQCN
 * @param matchedTypeFile project-relative matched source file
 * @param handlers declaration-ordered handlers
 * @param evidence bounded safe evidence
 * @param attemptedStrategies deterministic strategy names
 */
public record DiscoverHandlersResult(
        String projectRootAbs,
        RouteTargetHints hints,
        List<String> additionalSourceRoots,
        int scannedJavaFiles,
        String framework,
        String controllerFqcn,
        String matchedTypeFile,
        List<RouteSynthesisHandler> handlers,
        List<String> evidence,
        List<String> attemptedStrategies) implements RouteSynthesisActionResult {

    /**
     * Defensively copies output collections.
     */
    public DiscoverHandlersResult {
        additionalSourceRoots = copy(additionalSourceRoots);
        handlers = copy(handlers);
        evidence = copy(evidence);
        attemptedStrategies = copy(attemptedStrategies);
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
