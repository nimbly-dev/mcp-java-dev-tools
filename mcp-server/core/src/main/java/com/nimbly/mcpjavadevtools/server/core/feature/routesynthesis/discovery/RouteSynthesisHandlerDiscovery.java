package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.RouteSynthesisHandlerDiscoveryResult;

import java.nio.file.Path;
import java.util.List;

/**
 * Contains framework-specific HTTP handler discovery behind a narrow Core boundary.
 */
@FunctionalInterface
public interface RouteSynthesisHandlerDiscovery {

    /**
     * Discovers deterministic HTTP handlers from contained Java source.
     *
     * @param projectRoot selected project root
     * @param additionalSourceRoots additional contained source roots
     * @param classHint controller FQCN hint
     * @return bounded discovery outcome
     */
    RouteSynthesisHandlerDiscoveryResult discover(
            Path projectRoot,
            List<Path> additionalSourceRoots,
            String classHint);
}
