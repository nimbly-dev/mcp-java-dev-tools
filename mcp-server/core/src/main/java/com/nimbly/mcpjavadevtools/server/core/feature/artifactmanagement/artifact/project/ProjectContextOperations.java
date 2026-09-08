package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactPathPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;

/** Purpose-owned operations for the project context Artifact family. */
public final class ProjectContextOperations {
    private final ArtifactManagementSupport support;
    private final ProjectContextContract contract;

    /** Creates the project context owner. */
    public ProjectContextOperations(ArtifactManagementSupport support) {
        this.support = support;
        this.contract = new ProjectContextContract();
    }

    /** Reads a project context Artifact. */
    public ArtifactManagementResult read(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            String projectName = support.resolveProject(workspace, request);
            JsonNode artifact = support.readProject(workspace, projectName);
            contract.validate(artifact);
            contract.validateScope(request, artifact);
            Map<String, Object> details = support.mapper().convertValue(
                    contract.details(projectName, artifact, request),
                    new TypeReference<Map<String, Object>>() { });
            return support.success(request, details);
        });
    }

    /** Validates a project context Artifact. */
    public ArtifactManagementResult validate(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            String projectName = support.resolveProject(workspace, request);
            JsonNode artifact = support.readProject(workspace, projectName);
            contract.validate(artifact);
            contract.validateScope(request, artifact);
            return support.success(request, Map.of(
                    "projectName", projectName,
                    "workspaceCount", artifact.path("workspaces").size(),
                    "valid", true));
        });
    }

    /** Upserts a project context Artifact. */
    public ArtifactManagementResult upsert(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            String projectName = request.text("projectName").orElseThrow(
                    () -> new ArtifactOperationException("project_selector_required", "projectName is required"));
            ArtifactPathPolicy.validateSegment(projectName);
            JsonNode payload = support.payload(request);
            contract.validate(payload);
            Path path = support.projectPath(workspace, projectName);
            boolean exists = Files.isRegularFile(path);
            boolean replace = request.booleanValue("replace").orElse(false);
            JsonNode output = replace || !exists
                    ? payload : contract.merge(support.jsonStore().read(path), payload);
            contract.validate(output);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("projectName", projectName);
            details.put("path", workspace.paths().relative(path));
            String updateMode = "created";
            if (exists) {
                updateMode = replace ? "replaced" : "merged";
            }
            details.put("updateMode", updateMode);
            Path database = null;
            boolean cleanupEligible = false;
            if (!exists) {
                database = workspace.paths().resolve(".mcpjvm", projectName, "run-state.sqlite");
                cleanupEligible = !Files.exists(database);
                Map<String, Object> ensured = support.runStateStore().ensure(database, projectName);
                details.put("stateStore", Map.of(
                        "provisioned", true,
                        "databasePathRel", workspace.paths().relative(database),
                        "schemaVersion", ensured.get("schemaVersion")));
            }
            persist(workspace, path, output, database, cleanupEligible, projectName);
            return support.success(request, details);
        });
    }

    private void persist(
            ArtifactManagementSupport.Workspace workspace,
            Path path,
            JsonNode output,
            Path database,
            boolean cleanupEligible,
            String projectName) {
        try {
            support.jsonStore().write(path, output);
        } catch (RuntimeException exception) {
            boolean removed = cleanupEligible && support.runStateStore()
                    .cleanupProvisioning(workspace.root(), database, projectName);
            throw new ArtifactOperationException(
                    "project_artifact_write_failed",
                    "project artifact could not be persisted after state-store provisioning",
                    Map.of("projectName", projectName,
                            "failedStep", "project_artifact_write",
                            "stateStoreCleanup", removed ? "removed" : "preserved"));
        }
    }

    /** Lists project context Artifacts. */
    public ArtifactManagementResult list(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            Path root = workspace.paths().resolve(".mcpjvm");
            var names = support.jsonStore().directories(root).stream()
                    .filter(name -> Files.isRegularFile(support.projectPath(workspace, name)))
                    .toList();
            return support.success(request, Map.of("projectNames", names));
        });
    }
}
