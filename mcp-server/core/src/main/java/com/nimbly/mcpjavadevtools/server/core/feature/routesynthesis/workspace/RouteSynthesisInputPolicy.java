package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace;

import java.util.List;

/**
 * Shared bounded input policy for Route Synthesis actions.
 */
public class RouteSynthesisInputPolicy {

    /** Maximum number of additional source roots accepted by compatibility behavior. */
    public static final int MAX_ADDITIONAL_SOURCE_ROOTS = 10;

    private RouteSynthesisInputPolicy() {
    }

    /**
     * Checks the TypeScript-compatible additional-root bound.
     *
     * @param roots requested roots
     * @return whether the request is within the bound
     */
    public static boolean additionalRootsWithinBound(List<?> roots) {
        return roots == null || roots.size() <= MAX_ADDITIONAL_SOURCE_ROOTS;
    }
}
