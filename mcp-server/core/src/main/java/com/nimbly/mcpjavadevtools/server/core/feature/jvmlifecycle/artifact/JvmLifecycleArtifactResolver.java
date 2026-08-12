package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact;

/**
 * Resolves one lifecycle artifact using the frozen precedence policy.
 */
@FunctionalInterface
public interface JvmLifecycleArtifactResolver {

    /**
     * Resolves and structurally validates one artifact.
     *
     * @param kind artifact kind
     * @return deterministic resolution
     */
    JvmLifecycleArtifactResolution resolve(JvmLifecycleArtifactKind kind);
}
