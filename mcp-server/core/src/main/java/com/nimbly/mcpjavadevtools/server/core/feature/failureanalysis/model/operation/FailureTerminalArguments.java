package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.operation;

/** Canonical terminal state supplied when runtime verification already ended. */
public record FailureTerminalArguments(
        String outcome,
        String reasonCode,
        String cleanupStatus,
        int attemptCount) {
}
