package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint;

import java.time.Duration;
import java.util.Objects;

/**
 * Configured default timeout, polling, and retry behavior for Probe calls.
 *
 * @param defaultTimeout default endpoint-call timeout
 * @param defaultPollInterval default polling interval
 * @param maxRetries default normal retry count
 * @param unreachableRetryEnabled whether unreachable retries are allowed
 * @param unreachableMaxRetries default unreachable retry count
 * @param bounds configured hard bounds
 */
public record ProbeRequestPolicy(
        Duration defaultTimeout,
        Duration defaultPollInterval,
        int maxRetries,
        boolean unreachableRetryEnabled,
        int unreachableMaxRetries,
        ProbeRequestBounds bounds) {

    /**
     * Clamps default values to the configured safety bounds.
     */
    public ProbeRequestPolicy {
        Objects.requireNonNull(bounds, "bounds must not be null");
        defaultTimeout = bounds.clampTimeout(defaultTimeout);
        defaultPollInterval = bounds.clampPollInterval(defaultPollInterval);
        maxRetries = bounds.clampRetries(maxRetries);
        unreachableMaxRetries = bounds.clampRetries(unreachableMaxRetries);
    }

    /**
     * Returns a requested timeout or the configured default within hard bounds.
     *
     * @param requestedTimeout optional action-specific timeout
     * @return requested or configured timeout
     */
    public Duration timeoutOrDefault(Duration requestedTimeout) {
        if (requestedTimeout == null) {
            return defaultTimeout;
        }
        return bounds.clampTimeout(requestedTimeout);
    }
}
