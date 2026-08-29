package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import com.fasterxml.jackson.databind.JsonNode;

/** Immutable normalized source row reconstructed from one run Artifact. */
record SqliteRunProjection(
        String suiteType,
        String planName,
        String runId,
        String status,
        int stepCount,
        int failedStepCount,
        Long startedAtEpochMs,
        Long completedAtEpochMs,
        String reasonCode,
        String executionProfile,
        String suiteRunId,
        String activePhase,
        String runDirPathRel,
        JsonNode artifact,
        boolean valid,
        String reason) {

    static SqliteRunProjection invalid(
            String suiteType, String planName, String runId, String path, String reason) {
        return new SqliteRunProjection(suiteType, planName, runId, "blocked", 0, 0,
                null, null, reason, null, null, null, path, null, false, reason);
    }
}
