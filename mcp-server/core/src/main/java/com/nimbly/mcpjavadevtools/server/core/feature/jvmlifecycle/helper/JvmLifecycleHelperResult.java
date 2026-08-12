package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.candidate.JvmCandidate;
import java.util.List;

/**
 * Validated lifecycle helper protocol result.
 *
 * @param operation helper operation
 * @param outcome helper outcome
 * @param reasonCode helper reason code
 * @param pids discovered process identifiers
 * @param candidates sanitized candidates
 * @param nonRestorableClasses bounded deactivation evidence
 */
public record JvmLifecycleHelperResult(
        String operation,
        String outcome,
        String reasonCode,
        List<String> pids,
        List<JvmCandidate> candidates,
        List<String> nonRestorableClasses) {

    /** Copies protocol collections. */
    public JvmLifecycleHelperResult {
        pids = List.copyOf(pids);
        candidates = List.copyOf(candidates);
        nonRestorableClasses = List.copyOf(nonRestorableClasses);
    }

    /** Creates a deterministic helper failure. */
    public static JvmLifecycleHelperResult failure(String operation, String reasonCode) {
        return new JvmLifecycleHelperResult(
                operation,
                "blocked",
                reasonCode,
                List.of(),
                List.of(),
                List.of());
    }
}
