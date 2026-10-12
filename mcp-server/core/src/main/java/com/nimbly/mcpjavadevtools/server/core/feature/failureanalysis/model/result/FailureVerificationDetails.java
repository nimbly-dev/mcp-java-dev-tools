package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result;

import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.verifyreproduction.FailureLineHitEvidence;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureFingerprint;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.investigation.FailureInvestigationContext;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.redaction.FailureAnalysisRedactor;

/** Typed bundle of runtime verification fields used to keep result factories bounded. */
public record FailureVerificationDetails(
        FailureFingerprint expectedFingerprint,
        FailureFingerprint observedFingerprint,
        FailureLineHitEvidence lineHit,
        FailureAttemptEvidence attemptEvidence,
        FailureInvestigationContext investigation,
        boolean diagnosisClaimed) {
    public FailureVerificationDetails {
        if (expectedFingerprint != null) {
            expectedFingerprint = new FailureFingerprint(
                    FailureAnalysisRedactor.text(expectedFingerprint.exceptionType(), Integer.MAX_VALUE),
                    FailureAnalysisRedactor.text(expectedFingerprint.rootCauseType(), Integer.MAX_VALUE),
                    FailureAnalysisRedactor.text(expectedFingerprint.nearestApplicationMethodKey(), Integer.MAX_VALUE),
                    expectedFingerprint.nearestApplicationFrame(), expectedFingerprint.normalizedMessage(),
                    expectedFingerprint.complete(), expectedFingerprint.incompletenessReasons());
        }
    }
}
