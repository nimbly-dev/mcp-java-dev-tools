package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.deactivate;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import java.util.Objects;

/**
 * Validated request for safe Sidecar Agent deactivation.
 */
public record DeactivateRequest(
        String pid,
        long expectedProcessStartEpochMs,
        boolean confirm) implements JvmLifecycleRequest {

    /** Validates the immutable mutation request. */
    public DeactivateRequest {
        Objects.requireNonNull(pid, "pid must not be null");
        if (expectedProcessStartEpochMs <= 0L || !confirm) {
            throw new IllegalArgumentException("deactivate request safety fields are invalid");
        }
    }

    @Override
    public JvmLifecycleAction action() {
        return JvmLifecycleAction.DEACTIVATE;
    }
}
