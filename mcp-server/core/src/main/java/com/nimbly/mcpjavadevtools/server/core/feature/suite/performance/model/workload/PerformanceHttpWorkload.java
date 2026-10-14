package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.workload;

import java.util.Map;

/** Validated HTTP request and concurrency schedule for the retained builtin executor. */
public record PerformanceHttpWorkload(String method, String url, Map<String, String> headers, String body,
        int timeoutMs, int concurrency, int rampUpSeconds, int durationSeconds) {
    public PerformanceHttpWorkload {
        headers = Map.copyOf(headers);
    }
}
