package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import java.util.List;

/**
 * Typed input reserved for bounded handler discovery migration.
 *
 * @param projectRootAbs selected project root
 * @param additionalSourceRoots optional additional source roots
 * @param classHint controller FQCN hint
 * @param probeId optional Probe registry selector
 * @param probeBaseUrl optional explicit Probe route
 */
public record DiscoverHandlersRequest(
        String projectRootAbs,
        List<String> additionalSourceRoots,
        String classHint,
        String probeId,
        String probeBaseUrl) implements RouteSynthesisRequest {

    /**
     * Defensively copies optional source roots.
     */
    public DiscoverHandlersRequest {
        additionalSourceRoots = additionalSourceRoots == null
                ? List.of()
                : List.copyOf(additionalSourceRoots);
    }

    @Override
    public RouteSynthesisAction action() {
        return RouteSynthesisAction.DISCOVER_HANDLERS;
    }
}
