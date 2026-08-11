package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, action-neutral endpoint request contract.
 *
 * @param endpoint resolved Sidecar endpoint
 * @param method HTTP method selected by an owning action
 * @param headers request headers after action-owned validation
 * @param payload bounded request payload
 * @param timeout bounded timeout
 * @param configuration configured endpoint policy and limits
 */
public record ProbeEndpointRequest(
        URI endpoint,
        String method,
        Map<String, String> headers,
        String payload,
        Duration timeout,
        ProbeEndpointConfiguration configuration) {

    /**
     * Defensively copies bounded transport values without performing I/O.
     */
    public ProbeEndpointRequest {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");
        method = method.trim().toUpperCase(Locale.ROOT);
        if (method.isEmpty()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        headers = configuration.limits().copyHeaders(headers);
        payload = configuration.limits().requestPayload(payload);
        timeout = configuration.requestPolicy().timeoutOrDefault(timeout);
    }
}
