package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint;

import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureFingerprint;
import java.util.List;

/** Sanitized result of parsing a Sidecar verify response. */
public record FailureVerificationEvidence(
        String outcome, FailureFingerprint observedFingerprint, List<String> reasons) {

    public FailureVerificationEvidence {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
