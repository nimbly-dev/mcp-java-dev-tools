package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactPathPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;

/** Purpose-owned project context Artifact boundary used by project actions. */
public final class ProjectContextArtifacts {
    private final ArtifactManagementSupport support;
    private final ProjectContextContract contract;

    /** Creates the project context owner. */
    public ProjectContextArtifacts(ArtifactManagementSupport support) {
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
            return support.success(request, contract.details(projectName, artifact, request));
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
            boolean replace = request.booleanValue("replace").orElse(false);
            JsonNode output = replace || !Files.isRegularFile(path)
                    ? payload : contract.merge(support.jsonStore().read(path), payload);
            support.jsonStore().write(path, output);
            return support.success(request, Map.of(
                    "projectName", projectName,
                    "path", workspace.paths().relative(path),
                    "updateMode", replace ? "replaced" : "merged"));
        });
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
