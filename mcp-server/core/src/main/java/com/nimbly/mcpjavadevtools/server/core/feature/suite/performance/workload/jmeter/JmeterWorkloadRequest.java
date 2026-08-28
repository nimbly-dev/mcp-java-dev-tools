package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter;

import java.nio.file.Path;
import java.util.Map;

/** Validated request for one generated HTTP JMeter workload. */
public record JmeterWorkloadRequest(
        Path runDirectory,
        String planName,
        String executable,
        String method,
        String url,
        Map<String, String> headers,
        String body,
        int timeoutMs,
        int concurrency,
        int rampUpSeconds,
        int durationSeconds) {

    /** Copies headers into an immutable map. */
    public JmeterWorkloadRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
