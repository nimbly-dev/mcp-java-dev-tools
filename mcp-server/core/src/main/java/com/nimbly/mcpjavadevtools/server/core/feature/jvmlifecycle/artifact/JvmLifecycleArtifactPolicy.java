package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact;

import java.nio.file.Path;
import java.util.Objects;
/**
 * Typed artifact locations supplied by Application composition.
 *
 * @param helperOverride optional explicit helper path
 * @param agentOverride optional explicit agent path
 * @param packagedDirectory directory containing the executable server JAR
 * @param localDevelopmentRoot repository root for exact development artifacts
 */
public record JvmLifecycleArtifactPolicy(
        String helperOverride,
        String agentOverride,
        Path packagedDirectory,
        Path localDevelopmentRoot) {

    /** Validates the path policy boundary. */
    public JvmLifecycleArtifactPolicy {
        Objects.requireNonNull(packagedDirectory, "packagedDirectory must not be null");
        Objects.requireNonNull(localDevelopmentRoot, "localDevelopmentRoot must not be null");
        packagedDirectory = packagedDirectory.toAbsolutePath().normalize();
        localDevelopmentRoot = localDevelopmentRoot.toAbsolutePath().normalize();
    }
}
