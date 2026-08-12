package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.attach;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import java.util.Objects;

/**
 * Validated request for safe Sidecar Agent attach.
 */
public record AttachRequest(
        String pid,
        long expectedProcessStartEpochMs,
        boolean confirm,
        String probeHost,
        int probePort,
        String include,
        String exclude) implements JvmLifecycleRequest {

    /** Validates the immutable mutation request. */
    public AttachRequest {
        Objects.requireNonNull(pid, "pid must not be null");
        Objects.requireNonNull(probeHost, "probeHost must not be null");
        if (expectedProcessStartEpochMs <= 0L || !confirm) {
            throw new IllegalArgumentException("attach request safety fields are invalid");
        }
        if (probePort < 1 || probePort > 65535) {
            throw new IllegalArgumentException("probePort is outside the supported range");
        }
    }

    @Override
    public JvmLifecycleAction action() {
        return JvmLifecycleAction.ATTACH;
    }
}
