package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact;

import java.nio.file.Path;
/**
 * Deterministic artifact resolution result.
 *
 * @param path resolved compatible artifact, or null when blocked
 * @param reasonCode stable resolution reason
 */
public record JvmLifecycleArtifactResolution(
        Path path,
        String reasonCode) {

    /** Creates a blocked resolution. */
    public static JvmLifecycleArtifactResolution blocked(String reasonCode) {
        return new JvmLifecycleArtifactResolution(null, reasonCode);
    }

    /** Creates a successful resolution. */
    public static JvmLifecycleArtifactResolution resolved(Path path) {
        return new JvmLifecycleArtifactResolution(path, "resolved");
    }

    /** Returns whether a compatible artifact was resolved. */
    public boolean isResolved() {
        return path != null;
    }
}
