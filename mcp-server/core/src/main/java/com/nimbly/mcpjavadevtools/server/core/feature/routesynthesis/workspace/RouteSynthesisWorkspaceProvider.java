package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;

import java.util.Optional;

/**
 * Supplies only the already-bound workspace snapshot to Route Synthesis.
 *
 * <p>The Application Adapter owns binding and configuration. Core never reads
 * environment variables or Spring configuration.</p>
 */
@FunctionalInterface
public interface RouteSynthesisWorkspaceProvider {

    /**
     * Returns the current bound workspace when one exists.
     *
     * @return bound workspace snapshot
     */
    Optional<RouteSynthesisWorkspaceSnapshot> current();
}
