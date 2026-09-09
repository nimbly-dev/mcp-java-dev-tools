package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Purpose-owned operations shared by performance, regression, and security plan families. */
public final class PlanOperations {
    private final ArtifactManagementSupport support;
    private final PlanContract contract;
    private final PlanReadProjection reads;

    /** Creates the plan owner. */
    public PlanOperations(ArtifactManagementSupport support) {
        this.support = support;
        this.contract = new PlanContract();
        this.reads = new PlanReadProjection(support, contract);
    }

    /** Reads one suite plan. */
    public ArtifactManagementResult read(ArtifactManagementRequest request, String suiteType) {
        return support.withWorkspace(request, workspace -> {
            String projectName = support.resolveProject(workspace, request);
            String planName = support.requiredSegment(request, "planName", "plan_name_required");
            return support.success(request,
                    reads.read(request, suiteType, projectName, planName, workspace));
        });
    }

    /** Validates one suite plan. */
    public ArtifactManagementResult validate(ArtifactManagementRequest request, String suiteType) {
        return support.withWorkspace(request, workspace -> {
            String projectName = support.resolveProject(workspace, request);
            String planName = support.requiredSegment(request, "planName", "plan_name_required");
            JsonNode metadata = null;
            if ("performance".equals(suiteType) || "regression".equals(suiteType)) {
                metadata = support.jsonStore().read(workspace.paths().resolve(
                        ".mcpjvm", projectName, "plans", suiteType, planName, "metadata.json"));
            }
            JsonNode contract = support.jsonStore().read(workspace.paths().resolve(
                    ".mcpjvm", projectName, "plans", suiteType, planName, "contract.json"));
            if ("performance".equals(suiteType)) {
                this.contract.validatePerformanceMetadata(metadata);
            }
            this.contract.validate(contract, suiteType);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("projectName", projectName);
            details.put("planName", planName);
            if ("performance".equals(suiteType)) {
                details.put("valid", true);
            }
            return support.success(request, details);
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
            String reportedPath = "performance".equals(suiteType)
                    ? workspace.paths().relative(plan)
                    : plan.toString();
            return support.success(request, Map.of(
                    "projectName", projectName,
                    "planName", planName,
                    "path", reportedPath));
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
