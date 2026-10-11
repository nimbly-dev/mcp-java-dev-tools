package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target;

import java.util.List;

/**
 * Deterministically selected Java class and method inventory.
 *
 * @param file project-relative source file
 * @param className simple class name
 * @param fqcn fully qualified class name when available
 * @param methods stable method inventory
 */
public record RouteTargetClass(
        String file,
        String className,
        String fqcn,
        List<RouteTargetMethod> methods) {

    /**
     * Defensively copies the method inventory.
     */
    public RouteTargetClass {
        methods = methods == null ? List.of() : List.copyOf(methods);
    }
}
