package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;

/** Resolves and validates the workload selected for an execution export. */
final class ExecutionExportWorkloadResolver {

    private final ArtifactManagementSupport support;

    ExecutionExportWorkloadResolver(ArtifactManagementSupport support) {
        this.support = support;
    }

    ExecutionExportWorkload.Workload resolve(
            ArtifactManagementSupport.Workspace workspace,
            String projectName,
            JsonNode projectArtifact,
            ArtifactManagementRequest request,
            ExecutionExportOptions options,
            String mode) {
        ExecutionExportWorkload.Workload workload = ExecutionExportWorkload.resolve(
                support.jsonStore(), workspace.paths(), projectName, projectArtifact,
                new ExecutionExportWorkload.WorkloadSelection(
                        request.text("executionProfile").orElse(null),
                        request.text("planName").orElse(null),
                        options.contextBindings()));
        if (workload.plans().stream().anyMatch(plan -> "security".equals(plan.suiteType()))) {
            throw new ArtifactOperationException(
                    "security_export_unsupported",
                    "Security Suite execution profiles do not produce workload replay exports");
        }
        if (workload.plans().stream().anyMatch(plan -> "performance".equals(plan.suiteType()))
                && "postman".equals(mode)) {
            throw new ArtifactOperationException(
                    "performance_export_mode_unsupported",
                    "performance exports support ps1 and sh modes only");
        }
        return workload;
    }
}
