package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result;

import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.verifyreproduction.FailureLineHitEvidence;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureFingerprint;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.investigation.FailureInvestigationContext;

/** Typed bundle of runtime verification fields used to keep result factories bounded. */
public record FailureVerificationDetails(
        FailureFingerprint expectedFingerprint,
        FailureFingerprint observedFingerprint,
        FailureLineHitEvidence lineHit,
        FailureAttemptEvidence attemptEvidence,
        FailureInvestigationContext investigation,
        boolean diagnosisClaimed) {
}
