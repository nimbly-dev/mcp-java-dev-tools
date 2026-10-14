package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.workload;

import java.util.List;
import java.util.Map;

/** Provider-neutral measured workload outcome; supplementary files remain provider owned. */
public record PerformanceWorkloadResult(boolean completed, String reasonCode, int totalRequests,
        int failedRequests, List<Long> latenciesMs, Map<String, Object> artifacts) {
    public PerformanceWorkloadResult {
        latenciesMs = List.copyOf(latenciesMs);
        artifacts = Map.copyOf(artifacts);
    }

    public static PerformanceWorkloadResult blocked(String reasonCode) {
        return new PerformanceWorkloadResult(false, reasonCode, 0, 0, List.of(), Map.of());
    }
}
