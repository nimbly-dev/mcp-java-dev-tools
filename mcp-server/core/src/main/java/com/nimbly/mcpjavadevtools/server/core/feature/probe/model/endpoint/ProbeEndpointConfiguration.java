package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint;

import java.util.Objects;

/**
 * Typed shared endpoint configuration for the Probe Feature.
 *
 * @param defaultBaseUrl optional fallback base URL when target selection permits it
 * @param paths Sidecar endpoint paths
 * @param requestPolicy bounded timing and retry policy
 * @param limits bounded endpoint transport limits
 */
public record ProbeEndpointConfiguration(
        String defaultBaseUrl,
        ProbeEndpointPaths paths,
        ProbeRequestPolicy requestPolicy,
        ProbeEndpointLimits limits) {

    /**
     * Normalizes an optional configured default without requiring one.
     */
    public ProbeEndpointConfiguration {
        defaultBaseUrl = normalize(defaultBaseUrl);
        Objects.requireNonNull(paths, "paths must not be null");
        Objects.requireNonNull(requestPolicy, "requestPolicy must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
    }

    /**
     * Returns whether a configured fallback base URL is available.
     *
     * @return whether a default base URL is configured
     */
    public boolean hasDefaultBaseUrl() {
        return defaultBaseUrl != null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
