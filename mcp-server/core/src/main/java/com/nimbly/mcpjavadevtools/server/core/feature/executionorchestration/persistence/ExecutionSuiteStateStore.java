package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence;

import java.util.Map;
import java.util.Optional;

/**
 * Owns durable canonical suite-run state without coupling orchestration to a filesystem implementation.
 */
public interface ExecutionSuiteStateStore {

    /** Reads the current canonical suite status Artifact when present. */
    Optional<Map<String, Object>> read(String projectName, String suiteRunId);

    /** Persists canonical suite status and returns its workspace-relative Artifact path. */
    Optional<String> write(String projectName, String suiteRunId, Map<String, Object> state);
}
