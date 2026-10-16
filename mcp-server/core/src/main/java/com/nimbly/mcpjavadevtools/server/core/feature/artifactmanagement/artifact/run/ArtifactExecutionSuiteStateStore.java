package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactPathPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactWorkspaceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence.ExecutionSuiteStateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Owns canonical suite checkpoints and their incremental SQLite continuation projection. */
public class ArtifactExecutionSuiteStateStore implements ExecutionSuiteStateStore {
    private final ArtifactWorkspaceProvider workspace;
    private final ArtifactJsonStore json;
    private final SqliteRunStateStore projections;
    private final ObjectMapper mapper;

    public ArtifactExecutionSuiteStateStore(ArtifactWorkspaceProvider workspace, ArtifactJsonStore json,
            SqliteRunStateStore projections, ObjectMapper mapper) {
        this.workspace = workspace;
        this.json = json;
        this.projections = projections;
        this.mapper = mapper;
    }

    @Override
    public Optional<Map<String, Object>> read(String projectName, String suiteRunId) {
        try {
            Path path = path(paths(), projectName, suiteRunId);
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            var value = json.read(path);
            if (value == null || !value.isObject()) {
                return Optional.empty();
            }
            return Optional.of(mapper.convertValue(value, new TypeReference<Map<String, Object>>() { }));
        } catch (ArtifactOperationException failure) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> write(String projectName, String suiteRunId, Map<String, Object> state) {
        try {
            ArtifactPathPolicy paths = paths();
            Path path = path(paths, projectName, suiteRunId);
            json.write(path, mapper.valueToTree(state));
            projections.projectSuite(paths.resolve(".mcpjvm", projectName, "run-state.sqlite"), projectName, suiteRunId);
            return Optional.of(paths.relative(path));
        } catch (ArtifactOperationException failure) {
            return Optional.empty();
        }
    }

    private ArtifactPathPolicy paths() {
        return new ArtifactPathPolicy(workspace.currentWorkspaceRoot().orElseThrow(() ->
                new ArtifactOperationException("workspace_context_missing", "No MCP workspace root is bound")));
    }

    private static Path path(ArtifactPathPolicy paths, String projectName, String suiteRunId) {
        return paths.resolve(".mcpjvm", projectName, "suite-runs", suiteRunId, "execution_orchestration.result.json");
    }
}
