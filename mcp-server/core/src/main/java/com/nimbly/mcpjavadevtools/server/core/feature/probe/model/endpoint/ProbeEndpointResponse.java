package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded raw response contract returned by a future endpoint-client adapter.
 *
 * @param statusCode HTTP response status
 * @param headers response headers
 * @param payload response payload before action-owned parsing and compaction
 * @param payloadBytes original bounded response bytes for an owned binary action
 * @param configuration configured endpoint policy and limits
 */
public record ProbeEndpointResponse(
        int statusCode,
        Map<String, String> headers,
        String payload,
        byte[] payloadBytes,
        ProbeEndpointConfiguration configuration) {

    /**
     * Defensively copies response values without interpreting action behavior.
     */
    public ProbeEndpointResponse {
        Objects.requireNonNull(configuration, "configuration must not be null");
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be a valid HTTP status");
        }
        headers = configuration.limits().copyHeaders(headers == null ? Map.of() : headers);
        payload = configuration.limits().responsePayload(payload);
        payloadBytes = configuration.limits().responsePayloadBytes(payloadBytes);
    }

    /**
     * Preserves existing text-response construction for JSON Sidecar endpoints.
     *
     * @param statusCode HTTP response status
     * @param headers response headers
     * @param payload bounded text response payload
     * @param configuration configured endpoint policy and limits
     */
    public ProbeEndpointResponse(
            int statusCode,
            Map<String, String> headers,
            String payload,
            ProbeEndpointConfiguration configuration) {
        this(statusCode, headers, payload, payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8), configuration);
    }

    /**
     * Returns a defensive copy of original bounded response bytes.
     *
     * @return bounded response bytes
     */
    @Override
    public byte[] payloadBytes() {
        return payloadBytes.clone();
    }
}
