package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.terminal;

import java.util.Set;

/** Previously terminal Failure Lens state supplied by the Skill Workflow. */
public record FailureTerminalState(
        String outcome,
        String reasonCode,
        String cleanupStatus,
        int attemptCount) {

    private static final Set<String> OUTCOMES = Set.of(
            "BLOCKED_AMBIGUOUS_JVM", "BLOCKED_MISSING_AUTH", "BLOCKED_MISSING_TRIGGER",
            "BLOCKED_USER_ACTION_REQUIRED", "BLOCKED_UNSAFE_OPERATION", "ENVIRONMENT_MISMATCH",
            "INCONCLUSIVE", "CANCELLED");
    private static final Set<String> CLEANUP = Set.of(
            "cleanup_confirmed", "cleanup_incomplete", "external_workflow_owned");

    public FailureTerminalState {
        outcome = required(outcome, "outcome");
        reasonCode = required(reasonCode, "reasonCode");
        cleanupStatus = required(cleanupStatus, "cleanupStatus");
        if (!OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("unsupported terminal outcome");
        }
        if (!CLEANUP.contains(cleanupStatus)) {
            throw new IllegalArgumentException("unsupported cleanup status");
        }
        if (attemptCount < 0 || attemptCount > 10) {
            throw new IllegalArgumentException("attemptCount must be between 0 and 10");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
