package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.policy;

import java.time.Duration;
import java.util.Objects;

/**
 * Hard lifecycle helper bounds that are not configurable by the MCP client.
 */
public record JvmLifecycleExecutionPolicy(
        String javaBinary,
        Duration initialTimeout,
        Duration attachReconciliationTimeout,
        int maximumOutputCharacters,
        ProbeHostPolicy probeHostPolicy) {

    /** Creates the frozen v0.1.9 execution policy. */
    public JvmLifecycleExecutionPolicy {
        Objects.requireNonNull(javaBinary, "javaBinary must not be null");
        Objects.requireNonNull(initialTimeout, "initialTimeout must not be null");
        Objects.requireNonNull(attachReconciliationTimeout,
                "attachReconciliationTimeout must not be null");
        Objects.requireNonNull(probeHostPolicy, "probeHostPolicy must not be null");
        if (javaBinary.isBlank() || maximumOutputCharacters != 65536) {
            throw new IllegalArgumentException("lifecycle execution policy is invalid");
        }
    }

    /** Returns the exact frozen policy. */
    public static JvmLifecycleExecutionPolicy frozen(String javaBinary, ProbeHostPolicy hosts) {
        return new JvmLifecycleExecutionPolicy(
                javaBinary == null || javaBinary.trim().isBlank() ? "java" : javaBinary.trim(),
                Duration.ofSeconds(15),
                Duration.ofSeconds(15),
                65536,
                hosts);
    }
}
