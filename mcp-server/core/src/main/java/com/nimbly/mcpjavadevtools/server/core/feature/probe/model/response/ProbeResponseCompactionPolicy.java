package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Explicit bounded response-compaction and diagnostic-redaction policy.
 *
 * @param includeExecutionPaths whether bounded execution paths may be retained
 * @param maximumStringLength maximum retained scalar string length
 * @param maximumExecutionPaths maximum retained execution-path count
 * @param maximumDiagnosticHeaders maximum retained diagnostic header count
 * @param maximumDiagnosticHeaderValueLength maximum retained diagnostic value length
 * @param safeDiagnosticValueHeaders allowlisted header names whose values are safe
 */
public record ProbeResponseCompactionPolicy(
        boolean includeExecutionPaths,
        int maximumStringLength,
        int maximumExecutionPaths,
        int maximumDiagnosticHeaders,
        int maximumDiagnosticHeaderValueLength,
        Set<String> safeDiagnosticValueHeaders) {

    private static final int HARD_MAXIMUM_STRING_LENGTH = 256;
    private static final int HARD_MAXIMUM_EXECUTION_PATHS = 32;
    private static final int HARD_MAXIMUM_DIAGNOSTIC_HEADERS = 64;
    private static final int HARD_MAXIMUM_DIAGNOSTIC_HEADER_VALUE_LENGTH = 256;
    private static final Set<String> CORE_SAFE_DIAGNOSTIC_VALUE_HEADERS = Set.of(
            "content-length",
            "content-type",
            "etag",
            "traceparent",
            "x-correlation-id",
            "x-request-id");

    /**
     * Validates limits and normalizes the safe header-name allowlist.
     */
    public ProbeResponseCompactionPolicy {
        validateLimit(maximumStringLength, HARD_MAXIMUM_STRING_LENGTH, "maximumStringLength");
        validateLimit(maximumExecutionPaths, HARD_MAXIMUM_EXECUTION_PATHS, "maximumExecutionPaths");
        validateLimit(maximumDiagnosticHeaders, HARD_MAXIMUM_DIAGNOSTIC_HEADERS, "maximumDiagnosticHeaders");
        validateLimit(
                maximumDiagnosticHeaderValueLength,
                HARD_MAXIMUM_DIAGNOSTIC_HEADER_VALUE_LENGTH,
                "maximumDiagnosticHeaderValueLength");
        Objects.requireNonNull(safeDiagnosticValueHeaders, "safeDiagnosticValueHeaders must not be null");
        safeDiagnosticValueHeaders = normalizedSafeHeaders(safeDiagnosticValueHeaders);
        validateSafeHeaders(safeDiagnosticValueHeaders);
    }

    /**
     * Returns whether a diagnostic header value is explicitly safe to expose.
     *
     * @param headerName diagnostic header name
     * @return whether the header value may be exposed
     */
    public boolean exposesHeaderValue(String headerName) {
        if (headerName == null) {
            return false;
        }
        return safeDiagnosticValueHeaders.contains(headerName.trim().toLowerCase(Locale.ROOT));
    }

    private static Set<String> normalizedSafeHeaders(Set<String> headerNames) {
        return headerNames.stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void validateSafeHeaders(Set<String> headerNames) {
        if (!CORE_SAFE_DIAGNOSTIC_VALUE_HEADERS.containsAll(headerNames)) {
            throw new IllegalArgumentException("safe diagnostic headers must be Core allowlist members");
        }
    }

    private static void validateLimit(int value, int hardMaximum, String fieldName) {
        if (value < 1) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        if (value > hardMaximum) {
            throw new IllegalArgumentException(fieldName + " exceeds the Core hard resource ceiling");
        }
    }
}
