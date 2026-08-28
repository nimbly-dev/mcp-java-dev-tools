package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence;

import java.util.Optional;

/**
 * Resolves the canonical writable Artifact directory for one orchestration plan run.
 *
 * <p>Application composition supplies the workspace-bound implementation. This
 * Core collaborator does not own filesystem discovery, Artifact writes, or
 * transport concerns.</p>
 */
public interface ExecutionRunDirectoryProvider {

    /** Resolves the directory only when the current workspace is available. */
    Optional<String> resolve(String projectName, String suiteType, String planName, String runId);
}
