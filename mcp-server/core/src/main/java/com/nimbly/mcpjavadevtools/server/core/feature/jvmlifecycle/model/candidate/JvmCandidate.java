package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.candidate;

import java.util.List;
/**
 * Sanitized, unverified JVM candidate compatible with the existing public output.
 *
 * @param pid process identifier
 * @param identityHint bounded display identity
 * @param identitySource identity provenance
 * @param frameworkHint deterministic framework inference
 * @param frameworkEvidence bounded inference evidence
 * @param processStartEpochMs process identity fence value
 */
public record JvmCandidate(
        String pid,
        String identityHint,
        String identitySource,
        String frameworkHint,
        List<String> frameworkEvidence,
        Long processStartEpochMs) {

    /** Defensively copies framework evidence. */
    public JvmCandidate {
        frameworkEvidence = List.copyOf(frameworkEvidence);
    }
}
