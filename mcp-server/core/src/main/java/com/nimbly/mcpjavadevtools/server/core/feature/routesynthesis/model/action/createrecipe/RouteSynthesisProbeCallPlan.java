package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic Probe call counts retained in a recipe execution plan. */
public record RouteSynthesisProbeCallPlan(
        int total,
        String verificationMethod,
        boolean actuated,
        int probeReset,
        int probeWaitForHit,
        int probeGetStatus,
        int probeEnable) {

    /** Exposes the TypeScript-compatible per-action count grouping. */
    @JsonProperty("byTool")
    public Map<String, Integer> byTool() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("probe_reset", probeReset);
        counts.put("probe_wait_for_hit", probeWaitForHit);
        counts.put("probe_get_status", probeGetStatus);
        counts.put("probe_enable", probeEnable);
        return counts;
    }
}
