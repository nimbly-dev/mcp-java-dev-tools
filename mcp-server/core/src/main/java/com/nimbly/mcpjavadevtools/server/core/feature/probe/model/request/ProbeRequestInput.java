package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Host-neutral scalar input used to construct a typed Probe request.
 *
 * <p>Hosts convert their transport fields to this record; capability validation
 * and action-specific request construction remain in Core.</p>
 */
public record ProbeRequestInput(
        String baseUrl,
        String probeId,
        Map<String, String> headers,
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

    /**
     * Defensively copies host-provided collections without applying capability policy.
     */
    public ProbeRequestInput {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        keys = keys == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(keys));
    }
}
