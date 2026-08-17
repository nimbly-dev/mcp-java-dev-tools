package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.policy;

import java.time.Duration;
import java.util.Objects;

/** Validated operational policy; hard ceilings cannot be disabled by configuration. */
public record FailureAnalysisPolicy(
        Duration defaultTimeout,
        int maximumTraceCharacters,
        int maximumResponsePayloadBytes,
        int maximumStringLength,
        int maximumFrames,
        int maximumSections) {

    private static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);
    private static final int HARD_TRACE_LIMIT = 200_000;
    private static final int HARD_RESPONSE_LIMIT = 1_048_576;
    private static final int HARD_STRING_LIMIT = 4_096;
    private static final int HARD_FRAMES_LIMIT = 64;
    private static final int HARD_SECTIONS_LIMIT = 32;

    public FailureAnalysisPolicy {
        defaultTimeout = clampTimeout(defaultTimeout);
        maximumTraceCharacters = clamp(maximumTraceCharacters, 1, HARD_TRACE_LIMIT);
        maximumResponsePayloadBytes = clamp(maximumResponsePayloadBytes, 1, HARD_RESPONSE_LIMIT);
        maximumStringLength = clamp(maximumStringLength, 1, HARD_STRING_LIMIT);
        maximumFrames = clamp(maximumFrames, 1, HARD_FRAMES_LIMIT);
        maximumSections = clamp(maximumSections, 1, HARD_SECTIONS_LIMIT);
    }

    /** @param requested requested per-call timeout @return bounded timeout */
    public Duration timeoutOrDefault(Duration requested) {
        return requested == null ? defaultTimeout : clampTimeout(requested);
    }

    private static Duration clampTimeout(Duration value) {
        Objects.requireNonNull(value, "timeout must not be null");
        if (value.compareTo(MIN_TIMEOUT) < 0) {
            return MIN_TIMEOUT;
        }
        if (value.compareTo(MAX_TIMEOUT) > 0) {
            return MAX_TIMEOUT;
        }
        return value;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
