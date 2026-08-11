package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeRuntimeHints;
import java.util.Map;
import java.util.Objects;

/**
 * Safe diagnostic outcome for one protected or public Probe endpoint.
 *
 * @param status closed endpoint availability classification
 * @param httpStatus HTTP response status when an endpoint responded
 * @param responseKey status response key when present
 * @param diagnosticHeaders redacted bounded response headers
 * @param runtime safe runtime hints from a status response when available
 */
public record ProbeCheckEndpointResult(
        ProbeCheckEndpointStatus status,
        Integer httpStatus,
        String responseKey,
        Map<String, String> diagnosticHeaders,
        ProbeRuntimeHints runtime) {

    /**
     * Defensively copies already-redacted diagnostic headers.
     */
    public ProbeCheckEndpointResult {
        Objects.requireNonNull(status, "status must not be null");
        diagnosticHeaders = diagnosticHeaders == null ? Map.of() : Map.copyOf(diagnosticHeaders);
    }

    /**
     * Returns whether the endpoint's response establishes availability.
     *
     * @return whether this endpoint is available
     */
    public boolean available() {
        return status == ProbeCheckEndpointStatus.AVAILABLE;
    }
}
