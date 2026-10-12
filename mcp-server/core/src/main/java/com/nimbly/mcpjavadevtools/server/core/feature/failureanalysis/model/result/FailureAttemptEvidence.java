package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result;

import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.redaction.FailureAnalysisRedactor;

/** Bounded attempt facts; capture identifiers are retained only when supplied. */
public record FailureAttemptEvidence(String captureId, String sidecarOutcome, Integer attemptCount) {
    public FailureAttemptEvidence {
        captureId = FailureAnalysisRedactor.text(captureId, Integer.MAX_VALUE);
    }
}
