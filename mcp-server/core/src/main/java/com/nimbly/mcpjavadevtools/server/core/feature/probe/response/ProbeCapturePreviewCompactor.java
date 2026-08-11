package com.nimbly.mcpjavadevtools.server.core.feature.probe.response;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Allowlist-based compaction for safe capture-preview fields.
 */
public class ProbeCapturePreviewCompactor {

    private static final List<String> ALLOWED_FIELDS = List.of(
            "available",
            "captureId",
            "capturedAtEpoch",
            "executionStartedAtEpoch",
            "executionEndedAtEpoch",
            "executionDurationMs",
            "threadAllocatedBytesDelta",
            "methodKey",
            "redactionMode",
            "truncatedAny");

    private ProbeCapturePreviewCompactor() {
    }

    /**
     * Returns only scalar allowlisted values from an untrusted runtime preview.
     *
     * @param preview untrusted runtime preview
     * @return bounded safe capture preview
     */
    public static Map<String, Object> compact(
            Map<String, ?> preview,
            ProbeResponseCompactionPolicy policy) {
        if (preview == null || preview.isEmpty()) {
            return Map.of();
        }
        Objects.requireNonNull(policy, "policy must not be null");
        Map<String, Object> compacted = new LinkedHashMap<>();
        for (String field : ALLOWED_FIELDS) {
            addAllowedValue(compacted, field, preview.get(field), policy);
        }
        addExecutionPaths(compacted, preview.get("executionPaths"), policy);
        return Map.copyOf(compacted);
    }

    private static void addAllowedValue(
            Map<String, Object> target,
            String field,
            Object value,
            ProbeResponseCompactionPolicy policy) {
        if (value instanceof String stringValue) {
            target.put(field, boundedString(stringValue, policy.maximumStringLength()));
        } else if (value instanceof Number || value instanceof Boolean) {
            target.put(field, value);
        }
    }

    private static void addExecutionPaths(
            Map<String, Object> target,
            Object value,
            ProbeResponseCompactionPolicy policy) {
        if (!policy.includeExecutionPaths() || !(value instanceof List<?> values)) {
            return;
        }
        List<String> paths = values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .limit(policy.maximumExecutionPaths())
                .map(path -> boundedString(path, policy.maximumStringLength()))
                .toList();
        if (!paths.isEmpty()) {
            target.put("executionPaths", paths);
        }
    }

    private static String boundedString(String value, int maximumLength) {
        if (value.length() <= maximumLength) {
            return value;
        }
        return value.substring(0, maximumLength);
    }
}
