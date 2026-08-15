package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe;

import java.util.List;

/**
 * Deterministic execution plan retained in a generated recipe.
 */
public record RouteSynthesisExecutionPlan(
        String selectedMode,
        String routingReason,
        List<RouteSynthesisExecutionStep> steps,
        RouteSynthesisProbeCallPlan probeCallPlan) {

    /** Defensively copies plan collections. */
    public RouteSynthesisExecutionPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (probeCallPlan == null) {
            probeCallPlan = new RouteSynthesisProbeCallPlan(0, "probe_wait_for_hit", false, 0, 0, 0, 0);
        }
    }
}
