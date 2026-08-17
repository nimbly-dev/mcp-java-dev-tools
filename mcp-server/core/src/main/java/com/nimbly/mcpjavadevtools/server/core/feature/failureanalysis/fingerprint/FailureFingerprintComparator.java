package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.fingerprint;

import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureFingerprint;
import java.util.Objects;

/** Re-checks Sidecar comparison output so a matched result cannot bypass Core invariants. */
public final class FailureFingerprintComparator {

    private FailureFingerprintComparator() {
    }

    /**
     * Compares the three required fingerprint fields.
     *
     * @return null when equal, otherwise the stable mismatch reason
     */
    public static String mismatchReason(FailureFingerprint expected, FailureFingerprint observed) {
        if (observed == null || !observed.complete()) {
            return "observed_fingerprint_missing";
        }
        if (!Objects.equals(expected.exceptionType(), observed.exceptionType())) {
            return "different_exception";
        }
        if (!Objects.equals(expected.rootCauseType(), observed.rootCauseType())) {
            return "different_root_cause";
        }
        if (!Objects.equals(expected.nearestApplicationMethodKey(), observed.nearestApplicationMethodKey())) {
            return "different_application_frame";
        }
        return null;
    }
}
