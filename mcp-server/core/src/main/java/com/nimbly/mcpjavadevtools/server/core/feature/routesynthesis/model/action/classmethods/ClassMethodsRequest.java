package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.classmethods;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import java.util.List;

/**
 * Typed input for deterministic class method inventory.
 *
 * @param projectRootAbs selected project root
 * @param additionalSourceRoots optional additional source roots
 * @param classHint exact class or FQCN hint
 * @param probeId optional Probe registry selector
 * @param probeBaseUrl optional explicit Probe route
 */
public record ClassMethodsRequest(
        String projectRootAbs,
        List<String> additionalSourceRoots,
        String classHint,
        String probeId,
        String probeBaseUrl) implements RouteSynthesisRequest {

    /**
     * Defensively copies optional source roots.
     */
    public ClassMethodsRequest {
        additionalSourceRoots = additionalSourceRoots == null
                ? List.of()
                : List.copyOf(additionalSourceRoots);
    }

    @Override
    public RouteSynthesisAction action() {
        return RouteSynthesisAction.CLASS_METHODS;
    }
}
