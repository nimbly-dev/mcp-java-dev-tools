package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result;

/** Bounded attempt facts; capture identifiers are retained only when supplied. */
public record FailureAttemptEvidence(String captureId, String sidecarOutcome, Integer attemptCount) {
}
