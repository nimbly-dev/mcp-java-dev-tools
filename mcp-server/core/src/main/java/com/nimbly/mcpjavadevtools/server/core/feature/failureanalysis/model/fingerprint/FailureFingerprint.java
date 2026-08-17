package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint;

import java.util.List;

/** Deterministic failure identity; it never represents a diagnosis by itself. */
public record FailureFingerprint(
        String exceptionType,
        String rootCauseType,
        String nearestApplicationMethodKey,
        FailureFrame nearestApplicationFrame,
        String normalizedMessage,
        boolean complete,
        List<String> incompletenessReasons) {

    public FailureFingerprint {
        incompletenessReasons = incompletenessReasons == null
                ? List.of() : List.copyOf(incompletenessReasons);
    }

    /** Creates the expected comparison key supplied by the caller. */
    public static FailureFingerprint expected(
            String exceptionType, String rootCauseType, String nearestApplicationMethodKey) {
        return new FailureFingerprint(
                exceptionType, rootCauseType, nearestApplicationMethodKey, null, null, true, List.of());
    }
}
