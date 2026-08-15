package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe;

/**
 * One deterministic recipe execution instruction.
 */
public record RouteSynthesisExecutionStep(
        String phase,
        String title,
        String instruction) {
}
