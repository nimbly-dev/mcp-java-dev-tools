package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;

/** Purpose-owned plan Artifact boundary shared by regression, performance, and security actions. */
public final class PlanArtifacts {
    private final ArtifactManagementSupport support;
    private final PlanContract contract;

    /** Creates the plan owner. */
    public PlanArtifacts(ArtifactManagementSupport support) {
        this.support = support;
        this.contract = new PlanContract();
    }

    /** Reads one suite plan. */
    public ArtifactManagementResult read(ArtifactManagementRequest request, String suiteType) {
        return support.withWorkspace(request, workspace -> {
            String projectName = support.resolveProject(workspace, request);
            String planName = support.requiredSegment(request, "planName", "plan_name_required");
            JsonNode metadata = support.jsonStore().read(workspace.paths().resolve(
                    ".mcpjvm", projectName, "plans", suiteType, planName, "metadata.json"));
            JsonNode contract = support.jsonStore().read(workspace.paths().resolve(
                    ".mcpjvm", projectName, "plans", suiteType, planName, "contract.json"));
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("metadata", metadata);
            artifact.put("contract", contract);
            artifact.put("plan", support.jsonStore().readText(workspace.paths().resolve(
                    ".mcpjvm", projectName, "plans", suiteType, planName, "plan.md")));
            return support.success(request, Map.of(
                    "projectName", projectName,
                    "planName", planName,
                    "artifact", artifact,
                    "summary", this.contract.summary(contract, suiteType)));
        });
    }

    /** Validates one suite plan. */
    public ArtifactManagementResult validate(ArtifactManagementRequest request, String suiteType) {
        return support.withWorkspace(request, workspace -> {
            String projectName = support.resolveProject(workspace, request);
            String planName = support.requiredSegment(request, "planName", "plan_name_required");
            JsonNode contract = support.jsonStore().read(workspace.paths().resolve(
                    ".mcpjvm", projectName, "plans", suiteType, planName, "contract.json"));
            if ("performance".equals(suiteType)) {
                this.contract.validatePerformanceMetadata(support.jsonStore().read(workspace.paths().resolve(
                        ".mcpjvm", projectName, "plans", suiteType, planName, "metadata.json")));
            }
            this.contract.validate(contract, suiteType);
            return support.success(request, Map.of(
                    "projectName", projectName, "planName", planName, "valid", true));
        });
    }

    /** Upserts one suite plan. */
    public ArtifactManagementResult upsert(ArtifactManagementRequest request, String suiteType) {
        return support.withWorkspace(request, workspace -> {
            String projectName = support.resolveProject(workspace, request);
            String planName = support.requiredSegment(request, "planName", "plan_name_required");
            JsonNode payload = support.payload(request);
            JsonNode metadata = payload.path("metadata");
            JsonNode contract = payload.path("contract");
            ArtifactManagementSupport.requireObject(
                    metadata, "plan_metadata_invalid", "plan metadata must be a JSON object");
            ArtifactManagementSupport.requireObject(
                    contract, "plan_contract_invalid", "plan contract must be a JSON object");
            if ("performance".equals(suiteType)) {
                this.contract.validatePerformanceMetadata(metadata);
            }
            this.contract.validate(contract, suiteType);
            Path plan = workspace.paths().resolve(
                    ".mcpjvm", projectName, "plans", suiteType, planName);
            support.jsonStore().write(workspace.paths().resolve(
                    ".mcpjvm", projectName, "plans", suiteType, planName, "metadata.json"), metadata);
            support.jsonStore().write(workspace.paths().resolve(
                    ".mcpjvm", projectName, "plans", suiteType, planName, "contract.json"), contract);
            support.writeOptionalText(workspace.paths().resolve(
                    ".mcpjvm", projectName, "plans", suiteType, planName, "plan.md"), payload.get("plan"));
            return support.success(request, Map.of(
                    "projectName", projectName,
                    "planName", planName,
                    "path", workspace.paths().relative(plan)));
        });
    }

    /** Lists one suite's plans. */
    public ArtifactManagementResult list(ArtifactManagementRequest request, String suiteType) {
        return support.withWorkspace(request, workspace -> {
            String projectName = support.resolveProject(workspace, request);
            Path plans = support.plansPath(workspace, projectName, suiteType);
            return support.success(request, Map.of(
                    "projectName", projectName,
                    "planNames", support.jsonStore().directories(plans)));
        });
    }
}
