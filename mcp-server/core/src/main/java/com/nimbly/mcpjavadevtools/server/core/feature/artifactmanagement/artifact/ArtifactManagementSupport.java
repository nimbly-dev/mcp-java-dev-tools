package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Transport-neutral workspace and result boundary shared by Artifact family owners. */
public final class ArtifactManagementSupport {

    private final ArtifactWorkspaceProvider workspaceProvider;
    private final ArtifactJsonStore jsonStore;
    private final SqliteRunStateStore runStateStore;
    private final ObjectMapper mapper;

    /** Creates the bounded workspace boundary. */
    public ArtifactManagementSupport(
            ArtifactWorkspaceProvider workspaceProvider,
            ArtifactJsonStore jsonStore,
            SqliteRunStateStore runStateStore,
            ObjectMapper mapper) {
        this.workspaceProvider = workspaceProvider;
        this.jsonStore = jsonStore;
        this.runStateStore = runStateStore;
        this.mapper = mapper;
    }

    public ArtifactJsonStore jsonStore() { return jsonStore; }
    public SqliteRunStateStore runStateStore() { return runStateStore; }
    public ObjectMapper mapper() { return mapper; }

    /** Runs one family operation against the checked workspace. */
    public ArtifactManagementResult withWorkspace(
            ArtifactManagementRequest request, WorkspaceOperation operation) {
        try {
            Path root = workspaceProvider.currentWorkspaceRoot()
                    .orElseThrow(() -> new ArtifactOperationException(
                            "workspace_context_missing", "No MCP workspace root is bound"));
            return operation.execute(new Workspace(root, new ArtifactPathPolicy(root)));
        } catch (ArtifactOperationException exception) {
            return failure(request, exception);
        } catch (RuntimeException exception) {
            return failure(request, new ArtifactOperationException(
                    "artifact_management_operation_failed", "Artifact operation failed",
                    Map.of("failedStep", "artifact_operation")));
        }
    }

    public ArtifactManagementResult failure(
            ArtifactManagementRequest request, ArtifactOperationException exception) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("artifactType", value(request.artifactType()));
        metadata.put("action", value(request.action()));
        metadata.putAll(exception.metadata());
        return ArtifactManagementResult.blocked(exception.reasonCode(), exception.getMessage(), metadata);
    }

    public ArtifactManagementResult success(
            ArtifactManagementRequest request, Map<String, Object> details) {
        return ArtifactManagementResult.success(request.artifactType(), request.action(), details);
    }

    public ArtifactManagementResult success(
            ArtifactManagementRequest request, String status, Map<String, Object> details) {
        return ArtifactManagementResult.success(request.artifactType(), request.action(), status, details);
    }

    public JsonNode readOrNotConfigured(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new ArtifactOperationException("probe_registry_not_configured",
                    "Probe registry configuration is not present");
        }
        return jsonStore.read(path);
    }

    public JsonNode payload(ArtifactManagementRequest request) {
        return request.child("payload").orElseThrow(() -> new ArtifactOperationException(
                "artifact_payload_required", "payload is required for upsert"));
    }

    public static void requireObject(JsonNode value, String reasonCode, String message) {
        if (value == null || !value.isObject()) {
            throw new ArtifactOperationException(reasonCode, message);
        }
    }

    public String resolveProject(Workspace workspace, ArtifactManagementRequest request) {
        Optional<String> selected = request.text("projectName");
        if (selected.isPresent()) {
            request.text("projectRootAbs").ifPresent(this::validateProjectRoot);
            ArtifactPathPolicy.validateSegment(selected.get());
            return selected.get();
        }
        Optional<String> root = request.text("projectRootAbs");
        if (root.isPresent()) {
            return resolveProjectByRoot(workspace, root.get());
        }
        List<String> names = projectNames(workspace);
        if (names.size() == 1) {
            return names.getFirst();
        }
        if (names.isEmpty()) {
            throw new ArtifactOperationException("project_artifact_missing", "No project Artifact was found");
        }
        throw new ArtifactOperationException("project_artifact_ambiguous",
                "Multiple project Artifacts exist; projectName is required", Map.of("projectNames", names));
    }

    private String resolveProjectByRoot(Workspace workspace, String value) {
        Path requestedRoot = validateProjectRoot(value);
        List<String> matches = projectNames(workspace).stream()
                .filter(name -> projectMatchesRoot(workspace, name, requestedRoot))
                .toList();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("failedStep", "project_resolution");
        metadata.put("projectRootAbs", requestedRoot.toString());
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.isEmpty()) {
            throw new ArtifactOperationException("project_artifact_missing",
                    "projectRootAbs does not match any project Artifact workspace", metadata);
        }
        metadata.put("projectNames", matches);
        throw new ArtifactOperationException("project_artifact_ambiguous",
                "projectRootAbs matches multiple project Artifacts", metadata);
    }

    private Path validateProjectRoot(String value) {
        Path root;
        try {
            root = Path.of(value.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new ArtifactOperationException("project_selector_invalid",
                    "projectRootAbs is invalid", Map.of("failedStep", "project_root_validation"));
        }
        if (!Files.isDirectory(root)) {
            throw new ArtifactOperationException("project_selector_invalid",
                    Files.exists(root) ? "projectRootAbs must be a directory" : "projectRootAbs does not exist",
                    Map.of("failedStep", "project_root_validation", "projectRootAbs", root.toString()));
        }
        return root;
    }

    private List<String> projectNames(Workspace workspace) {
        return jsonStore.directories(workspace.paths.resolve(".mcpjvm")).stream()
                .filter(name -> Files.isRegularFile(projectPath(workspace, name)))
                .toList();
    }

    private boolean projectMatchesRoot(Workspace workspace, String projectName, Path requestedRoot) {
        try {
            JsonNode artifact = readProject(workspace, projectName);
            for (JsonNode candidate : artifact.path("workspaces")) {
                if (candidate.path("projectRoot").isTextual()
                        && Path.of(candidate.path("projectRoot").asText()).toAbsolutePath().normalize()
                        .equals(requestedRoot)) {
                    return true;
                }
            }
        } catch (ArtifactOperationException | InvalidPathException exception) {
            return false;
        }
        return false;
    }

    public String requiredProject(ArtifactManagementRequest request) {
        return request.text("projectName").map(value -> {
            ArtifactPathPolicy.validateSegment(value);
            return value;
        }).orElseThrow(() -> new ArtifactOperationException(
                "project_selector_required", "projectName is required for this action"));
    }

    public Path projectPath(Workspace workspace, String projectName) {
        return workspace.paths.resolve(".mcpjvm", projectName, "projects.json");
    }

    public JsonNode readProject(Workspace workspace, String projectName) {
        return jsonStore.read(projectPath(workspace, projectName));
    }

    public Path plansPath(Workspace workspace, String projectName, String suiteType) {
        if (!List.of("regression", "performance", "security").contains(suiteType)) {
            throw new ArtifactOperationException("suite_type_invalid", "suiteType is unsupported");
        }
        return workspace.paths.resolve(".mcpjvm", projectName, "plans", suiteType);
    }

    public String suiteType(ArtifactManagementRequest request) {
        return request.text("suiteType").orElse("regression");
    }

    public String requiredSegment(
            ArtifactManagementRequest request, String field, String reasonCode) {
        String value = request.text(field).orElseThrow(
                () -> new ArtifactOperationException(reasonCode, field + " is required"));
        ArtifactPathPolicy.validateSegment(value);
        return value;
    }

    /** Writes optional bounded plan Markdown through the JSON store's atomic writer. */
    public void writeOptionalText(Path path, JsonNode value) {
        if (value != null && value.isTextual()) {
            jsonStore.writeText(path, value.asText());
        }
    }

    private static String value(ArtifactType type) { return type == null ? "unknown" : type.value(); }
    private static String value(ArtifactAction action) { return action == null ? "unknown" : action.value(); }

    /** Workspace and symlink policy bound for one operation. */
    public record Workspace(Path root, ArtifactPathPolicy paths) { }

    @FunctionalInterface
    public interface WorkspaceOperation {
        ArtifactManagementResult execute(Workspace workspace);
    }
}
