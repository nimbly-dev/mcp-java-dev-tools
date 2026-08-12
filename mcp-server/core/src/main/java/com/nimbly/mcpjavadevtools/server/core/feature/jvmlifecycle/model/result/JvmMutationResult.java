package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result;

import java.util.List;
import java.util.Objects;

/**
 * Structured attach or deactivation output.
 *
 * @param operation helper operation
 * @param outcome helper lifecycle outcome
 * @param pid selected target PID
 * @param expectedProcessStartEpochMs identity fence
 * @param probeHost selected Probe host, or null for deactivation
 * @param probePort selected Probe port, or null for deactivation
 * @param nonRestorableClasses bounded deactivation evidence
 */
public record JvmMutationResult(
        String operation,
        String outcome,
        String pid,
        long expectedProcessStartEpochMs,
        String probeHost,
        Integer probePort,
        List<String> nonRestorableClasses) implements JvmLifecycleActionResult {

    /** Validates and copies structured lifecycle evidence. */
    public JvmMutationResult {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(pid, "pid must not be null");
        nonRestorableClasses = List.copyOf(nonRestorableClasses);
    }
}
