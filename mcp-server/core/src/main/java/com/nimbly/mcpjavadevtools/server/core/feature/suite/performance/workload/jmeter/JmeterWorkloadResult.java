package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter;

import java.nio.file.Path;
import java.util.List;

/** Outcome and bounded metrics from one JMeter workload. */
public record JmeterWorkloadResult(
        boolean completed,
        String reasonCode,
        int totalRequests,
        int failedRequests,
        List<Long> latenciesMs,
        Path jmxPath,
        Path jtlPath,
        Path logPath) {

    /** Returns a deterministic blocked workload outcome. */
    public static JmeterWorkloadResult blocked(String reasonCode, Path jmxPath, Path jtlPath, Path logPath) {
        return new JmeterWorkloadResult(false, reasonCode, 0, 0, List.of(), jmxPath, jtlPath, logPath);
    }
}
