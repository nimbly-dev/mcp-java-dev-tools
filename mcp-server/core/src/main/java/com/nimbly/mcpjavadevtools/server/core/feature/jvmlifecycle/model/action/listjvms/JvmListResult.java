package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.listjvms;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.candidate.JvmCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleActionResult;
import java.util.List;

/**
 * Bounded JVM discovery output owned by the list_jvms action.
 *
 * @param jvms sanitized unverified candidates
 */
public record JvmListResult(List<JvmCandidate> jvms) implements JvmLifecycleActionResult {

    /** Defensively copies the candidate list. */
    public JvmListResult {
        jvms = List.copyOf(jvms);
    }
}
