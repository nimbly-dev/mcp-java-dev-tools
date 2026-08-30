package com.nimbly.mcpjavadevtools.server.core.operation;

/** Canonical terminal state supplied when runtime verification already ended. */
public record FailureTerminalArguments(
        String outcome,
        String reasonCode,
        String cleanupStatus,
        int attemptCount) {
}
