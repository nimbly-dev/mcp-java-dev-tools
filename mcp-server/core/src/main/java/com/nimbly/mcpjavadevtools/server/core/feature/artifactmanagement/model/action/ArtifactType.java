package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action;

import java.util.Arrays;
import java.util.Optional;

/** Closed Artifact family allowlist exposed by artifact_management. */
public enum ArtifactType {
    PROBE_CONFIG("probe_config"),
    PROJECT_CONTEXT("project_context"),
    PERFORMANCE_PLAN("performance_plan"),
    REGRESSION_PLAN("regression_plan"),
    SECURITY_PLAN("security_plan"),
    RUN_RESULT("run_result"),
    EXECUTION_EXPORT("execution_export");

    private final String value;

    ArtifactType(String value) {
        this.value = value;
    }

    /** @return stable MCP value */
    public String value() {
        return value;
    }

    /** @param value boundary value @return matching Artifact type */
    public static Optional<ArtifactType> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(type -> type.value.equals(value)).findFirst();
    }
}
