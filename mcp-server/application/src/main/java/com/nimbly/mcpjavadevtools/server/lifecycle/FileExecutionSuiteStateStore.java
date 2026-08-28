package com.nimbly.mcpjavadevtools.server.lifecycle;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence.ExecutionSuiteStateStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;

/** Workspace-bound implementation of canonical execution-orchestration suite state storage. */
public final class FileExecutionSuiteStateStore implements ExecutionSuiteStateStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private final WorkspaceContext workspace;
    private final ObjectMapper mapper;

    /** Creates the application-owned filesystem adapter. */
    public FileExecutionSuiteStateStore(WorkspaceContext workspace, ObjectMapper mapper) {
        this.workspace = workspace;
        this.mapper = mapper;
    }

    @Override
    public Optional<Map<String, Object>> read(String projectName, String suiteRunId) {
        Path path = path(projectName, suiteRunId);
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Map.copyOf(mapper.readValue(path.toFile(), MAP)));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> write(String projectName, String suiteRunId, Map<String, Object> state) {
        Path target = path(projectName, suiteRunId);
        if (target == null) {
            return Optional.empty();
        }
        try {
            writeAtomically(target, state);
            return Optional.of(relative(projectName, suiteRunId));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private Path path(String projectName, String suiteRunId) {
        Path root = workspace.snapshot().root();
        if (root == null || !safe(projectName) || !safe(suiteRunId)) {
            return null;
        }
        return root.resolve(".mcpjvm").resolve(projectName).resolve("suite-runs").resolve(suiteRunId)
                .resolve("execution_orchestration.result.json").normalize();
    }

    private void writeAtomically(Path target, Map<String, Object> state) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "suite-state-", ".json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), state);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean safe(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    }

    private static String relative(String projectName, String suiteRunId) {
        return ".mcpjvm/" + projectName + "/suite-runs/" + suiteRunId + "/execution_orchestration.result.json";
    }
}
