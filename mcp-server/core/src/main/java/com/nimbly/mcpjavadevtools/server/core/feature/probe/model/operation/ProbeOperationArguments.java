package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.operation;

import java.util.List;
import java.util.Map;

/** Canonical Probe arguments without the outer Tool action discriminator. */
public record ProbeOperationArguments(
        String baseUrl,
        String probeId,
        ProbeHttpArguments http,
        Integer timeoutMs,
        String key,
        List<String> keys,
        Integer lineHint,
        String className,
        Integer pollIntervalMs,
        Integer maxRetries,
        String captureId,
        String action,
        String sessionId,
        String actuatorId,
        String targetKey,
        Boolean returnBoolean,
        Long ttlMs,
        String provider,
        String event,
        Long intervalNanos,
        String outputPath,
        String outputFormat) {

    public ProbeOperationArguments {
        keys = keys == null ? List.of() : List.copyOf(keys);
        if (http == null) {
            http = new ProbeHttpArguments(Map.of());
        }
    }
}
