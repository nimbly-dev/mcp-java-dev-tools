package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Purpose-owned run Artifact boundary used by run-state actions. */
public final class RunResultArtifacts {
    private final ArtifactManagementSupport support;

    /** Creates the run-result owner. */
    public RunResultArtifacts(ArtifactManagementSupport support) {
        this.support = support;
    }

    /** Reads one run and its continuation/evidence state. */
    public ArtifactManagementResult read(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            String projectName = support.resolveProject(workspace, request);
            String suiteType = support.suiteType(request);
            String planName = support.requiredSegment(request, "planName", "plan_name_required");
            String runId = support.requiredSegment(request, "runId", "run_id_required");
            Path run = workspace.paths().resolve(
                    ".mcpjvm", projectName, "plans", suiteType, planName, "runs", runId);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("projectName", projectName);
            details.put("planName", planName);
            details.put("runId", runId);
            details.put("files", support.jsonStore().files(run));
            Path executionResult = workspace.paths().resolve(
                    ".mcpjvm", projectName, "plans", suiteType, planName, "runs", runId,
                    "execution.result.json");
            Path legacyResult = workspace.paths().resolve(
                    ".mcpjvm", projectName, "plans", suiteType, planName, "runs", runId,
                    "result.json");
            Path resultPath = Files.isRegularFile(executionResult) ? executionResult : legacyResult;
            if (Files.isRegularFile(resultPath)) {
                details.put("artifact", support.jsonStore().read(resultPath));
            }
            addOptionalRunState(details, new RunStatePath(workspace, projectName, suiteType, planName, runId),
                    "continuation", "continuation.json");
            addOptionalRunState(details, new RunStatePath(workspace, projectName, suiteType, planName, runId),
                    "correlation", "correlation.json");
            addOptionalRunState(details, new RunStatePath(workspace, projectName, suiteType, planName, runId),
                    "watchers", "watchers.json");
            addOptionalRunState(details, new RunStatePath(workspace, projectName, suiteType, planName, runId),
                    "externalVerification", "external-verification.json");
            return support.success(request, details);
        });
    }

    /** Lists runs for one plan. */
    public ArtifactManagementResult list(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            String projectName = support.resolveProject(workspace, request);
            String suiteType = support.suiteType(request);
            String planName = support.requiredSegment(request, "planName", "plan_name_required");
            Path runs = workspace.paths().resolve(
                    ".mcpjvm", projectName, "plans", suiteType, planName, "runs");
            return support.success(request, Map.of(
                    "projectName", projectName,
                    "planName", planName,
                    "runIds", support.jsonStore().directories(runs)));
        });
    }

    /** Rebuilds all run-state surfaces transactionally. */
    public ArtifactManagementResult rebuild(ArtifactManagementRequest request) {
        return runStateOperation(request, "rebuild");
    }

    /** Backfills legacy correlation state idempotently. */
    public ArtifactManagementResult backfill(ArtifactManagementRequest request) {
        String stateSurface = request.text("stateSurface").orElse("correlation_state");
        if (!"correlation_state".equals(stateSurface)) {
            return support.failure(request, new ArtifactOperationException(
                    "state_surface_invalid", "run_result backfill only supports correlation_state"));
        }
        return runStateOperation(request, "backfill");
    }

    /** Records the state-store cutover marker atomically. */
    public ArtifactManagementResult cutover(ArtifactManagementRequest request) {
        return runStateOperation(request, "cutover");
    }

    /** Queries a selected run-state surface. */
    public ArtifactManagementResult query(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            String projectName = requiredProject(request, "run_state_query_invalid",
                    "projectName is required for run_state queries");
            String stateSurface = request.text("stateSurface").orElse("run_state");
            ObjectNode queryInput = request.child("query")
                    .map(JsonNode::deepCopy)
                    .map(node -> (ObjectNode) node)
                    .orElseGet(() -> support.mapper().createObjectNode());
            request.text("planName").ifPresent(value -> putIfMissing(queryInput, "planName", value));
            request.text("runId").ifPresent(value -> putIfMissing(queryInput, "runId", value));
            request.text("executionProfile").ifPresent(value -> putIfMissing(queryInput, "executionProfile", value));
            request.text("suiteType").ifPresent(value -> putIfMissing(queryInput, "suiteType", value));
            if (request.input().path("strict").isBoolean()) {
                queryInput.put("strict", request.input().path("strict").booleanValue());
            }
            Map<String, Object> query = support.runStateStore().query(
                    workspace.paths().resolve(".mcpjvm", projectName, "run-state.sqlite"),
                    projectName,
                    stateSurface,
                    queryInput);
            return support.success(request, Map.of("projectName", projectName, "query", query));
        });
    }

    /** Applies bounded retention cleanup. */
    public ArtifactManagementResult cleanup(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            String projectName = requiredProject(request, "state_store_retention_invalid",
                    "projectName is required for retention cleanup");
            boolean dryRun = request.child("retention")
                    .flatMap(node -> RunQueryPolicy.optionalBoolean(node, "dryRun"))
                    .orElse(true);
            JsonNode retention = request.child("retention").orElse(null);
            int olderThanDays = RunQueryPolicy.boundedInteger(
                    retention, "terminalOlderThanDays", 90, 1, 3650);
            int keepMostRecent = RunQueryPolicy.boundedInteger(
                    retention, "keepMostRecentTerminalRuns", 1000, 0, 100000);
            int maxDeleteBatch = RunQueryPolicy.boundedInteger(
                    retention, "maxDeleteBatch", 500, 1, 500);
            Map<String, Object> result = support.runStateStore().cleanup(
                    workspace.paths().resolve(".mcpjvm", projectName, "run-state.sqlite"),
                    projectName, dryRun, olderThanDays, keepMostRecent, maxDeleteBatch);
            return support.success(request, Map.of("projectName", projectName, "cleanup", result));
        });
    }

    private ArtifactManagementResult runStateOperation(
            ArtifactManagementRequest request,
            String operation) {
        return support.withWorkspace(request, workspace -> {
            String projectName = requiredProject(request,
                    "rebuild".equals(operation) ? "state_store_rebuild_source_invalid"
                            : "backfill".equals(operation) ? "legacy_backfill_source_invalid"
                                    : "project_selector_required",
                    "rebuild".equals(operation)
                            ? "projectName is required for state-store rebuild"
                            : "backfill".equals(operation)
                                    ? "projectName is required for legacy correlation backfill"
                                    : "projectName is required");
            Path database = workspace.paths().resolve(".mcpjvm", projectName, "run-state.sqlite");
            Map<String, Object> result = switch (operation) {
                case "rebuild" -> support.runStateStore().rebuild(database, projectName,
                        request.input().path("strict").asBoolean(false));
                case "backfill" -> support.runStateStore().backfill(database, projectName);
                case "cutover" -> support.runStateStore().cutover(database, projectName);
                default -> throw new ArtifactOperationException(
                        "run_state_action_invalid", "unsupported run-state action");
            };
            return support.success(request, Map.of("projectName", projectName, operation, result));
        });
    }

    private void addOptionalRunState(
            Map<String, Object> details,
            RunStatePath statePath,
            String outputName,
            String fileName) {
        Path path = statePath.workspace().paths().resolve(
                ".mcpjvm", statePath.projectName(), "plans", statePath.suiteType(),
                statePath.planName(), "runs", statePath.runId(), fileName);
        if (Files.isRegularFile(path)) {
            details.put(outputName, support.jsonStore().read(path));
        }
    }

    private static void putIfMissing(ObjectNode target, String field, String value) {
        if (!target.has(field)) {
            target.put(field, value);
        }
    }

    private String requiredProject(
            ArtifactManagementRequest request, String reasonCode, String message) {
        return request.text("projectName").map(value -> {
            com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactPathPolicy
                    .validateSegment(value);
            return value;
        }).orElseThrow(() -> new ArtifactOperationException(reasonCode, message));
    }

    private record RunStatePath(
            ArtifactManagementSupport.Workspace workspace,
            String projectName,
            String suiteType,
            String planName,
            String runId) {
    }
}
