package com.nimbly.mcpjavadevtools.server.core.feature.probe.response;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Removes sensitive header values from bounded Probe diagnostics.
 */
public class ProbeHeaderRedactor {

    /**
     * Stable replacement for every sensitive header value.
     */
    public static final String REDACTED_VALUE = "[REDACTED]";

    private ProbeHeaderRedactor() {
    }

    /**
     * Redacts sensitive values and bounds retained header values.
     *
     * @param headers untrusted headers
     * @return safe bounded header map
     */
    public static Map<String, String> redact(
            Map<String, String> headers,
            ProbeResponseCompactionPolicy policy) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        Map<String, String> safeHeaders = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            addHeader(safeHeaders, header, policy);
            if (safeHeaders.size() == policy.maximumDiagnosticHeaders()) {
                break;
            }
        }
        return Map.copyOf(safeHeaders);
    }

    private static void addHeader(
            Map<String, String> safeHeaders,
            Map.Entry<String, String> header,
            ProbeResponseCompactionPolicy policy) {
        if (header == null || header.getKey() == null) {
            return;
        }
        String name = header.getKey().trim();
        if (name.isEmpty()) {
            return;
        }
        String value = policy.exposesHeaderValue(name)
                ? boundedValue(header.getValue(), policy.maximumDiagnosticHeaderValueLength())
                : REDACTED_VALUE;
        safeHeaders.put(name, value);
    }

    private static String boundedValue(String value, int maximumLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maximumLength) {
            return value;
        }
        return value.substring(0, maximumLength);
    }
}
