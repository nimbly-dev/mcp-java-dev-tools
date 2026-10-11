package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target;

/**
 * Minimal class candidate used for deterministic class ambiguity output.
 *
 * @param file project-relative source file
 * @param className simple class name
 * @param fqcn fully qualified class name when available
 */
public record RouteTargetClassMatch(
        String file,
        String className,
        String fqcn) {
}
