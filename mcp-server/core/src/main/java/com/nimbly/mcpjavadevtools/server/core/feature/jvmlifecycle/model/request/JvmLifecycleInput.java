package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request;

/**
 * Nullable transport-independent values used by the bounded request factory.
 */
public record JvmLifecycleInput(
        String pid,
        Long expectedProcessStartEpochMs,
        Boolean confirm,
        String probeHost,
        Integer probePort,
        String include,
        String exclude) {
}
