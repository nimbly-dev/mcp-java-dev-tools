package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint;

import java.time.Duration;
import java.util.Objects;

/**
 * Configured hard bounds for Probe timeouts, polling, and retries.
 *
 * @param minimumTimeout minimum endpoint timeout
 * @param maximumTimeout maximum endpoint timeout
 * @param minimumPollInterval minimum poll interval
 * @param maximumPollInterval maximum poll interval
 * @param minimumRetries minimum retry count
 * @param maximumRetries maximum retry count
 */
public record ProbeRequestBounds(
        Duration minimumTimeout,
        Duration maximumTimeout,
        Duration minimumPollInterval,
        Duration maximumPollInterval,
        int minimumRetries,
        int maximumRetries) {

    private static final Duration HARD_MINIMUM_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration HARD_MAXIMUM_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration HARD_MINIMUM_POLL_INTERVAL = Duration.ofMillis(100);
    private static final Duration HARD_MAXIMUM_POLL_INTERVAL = Duration.ofSeconds(5);
    private static final int HARD_MINIMUM_RETRIES = 1;
    private static final int HARD_MAXIMUM_RETRIES = 10;

    /**
     * Validates a non-empty, ordered range within Core-owned safety ceilings.
     */
    public ProbeRequestBounds {
        minimumTimeout = positive(minimumTimeout, "minimumTimeout");
        maximumTimeout = positive(maximumTimeout, "maximumTimeout");
        minimumPollInterval = positive(minimumPollInterval, "minimumPollInterval");
        maximumPollInterval = positive(maximumPollInterval, "maximumPollInterval");
        validateConfiguredRange(
                minimumTimeout,
                maximumTimeout,
                HARD_MINIMUM_TIMEOUT,
                HARD_MAXIMUM_TIMEOUT,
                "timeout");
        validateConfiguredRange(
                minimumPollInterval,
                maximumPollInterval,
                HARD_MINIMUM_POLL_INTERVAL,
                HARD_MAXIMUM_POLL_INTERVAL,
                "poll interval");
        validateConfiguredRetryRange(minimumRetries, maximumRetries);
    }

    /**
     * Clamps a requested timeout to this configured range.
     *
     * @param timeout requested timeout
     * @return bounded timeout
     */
    public Duration clampTimeout(Duration timeout) {
        return clampDuration(timeout, minimumTimeout, maximumTimeout, "timeout");
    }

    /**
     * Clamps a requested poll interval to this configured range.
     *
     * @param pollInterval requested poll interval
     * @return bounded poll interval
     */
    public Duration clampPollInterval(Duration pollInterval) {
        return clampDuration(pollInterval, minimumPollInterval, maximumPollInterval, "pollInterval");
    }

    /**
     * Clamps a requested retry count to this configured range.
     *
     * @param retries requested retry count
     * @return bounded retry count
     */
    public int clampRetries(int retries) {
        if (retries < minimumRetries) {
            return minimumRetries;
        }
        return Math.min(retries, maximumRetries);
    }

    private static Duration positive(Duration value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static void validateConfiguredRange(
            Duration minimum,
            Duration maximum,
            Duration hardMinimum,
            Duration hardMaximum,
            String rangeName) {
        if (minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(rangeName + " bounds must be ordered");
        }
        if (minimum.compareTo(hardMinimum) < 0 || maximum.compareTo(hardMaximum) > 0) {
            throw new IllegalArgumentException(rangeName + " bounds exceed Core hard safety ceilings");
        }
    }

    private static void validateConfiguredRetryRange(int minimum, int maximum) {
        if (minimum > maximum) {
            throw new IllegalArgumentException("retry bounds must be ordered");
        }
        if (minimum < HARD_MINIMUM_RETRIES || maximum > HARD_MAXIMUM_RETRIES) {
            throw new IllegalArgumentException("retry bounds exceed Core hard safety ceilings");
        }
    }

    private static Duration clampDuration(
            Duration value,
            Duration minimum,
            Duration maximum,
            String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.compareTo(minimum) < 0) {
            return minimum;
        }
        if (value.compareTo(maximum) > 0) {
            return maximum;
        }
        return value;
    }
}
