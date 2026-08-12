package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact;

/**
 * Lifecycle artifacts resolved by the MCP-side helper boundary.
 */
public enum JvmLifecycleArtifactKind {
    HELPER("jvm-attach-helper.jar", "attach_helper_unavailable"),
    AGENT("sidecar-agent.jar", "agent_artifact_unavailable");

    private final String packagedName;
    private final String unavailableReason;

    JvmLifecycleArtifactKind(String packagedName, String unavailableReason) {
        this.packagedName = packagedName;
        this.unavailableReason = unavailableReason;
    }

    /** Returns the exact packaged-distribution file name. */
    public String packagedName() {
        return packagedName;
    }

    /** Returns the stable unavailable reason code. */
    public String unavailableReason() {
        return unavailableReason;
    }
}
