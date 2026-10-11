package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.RunResultOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import java.util.Map;
import java.util.Objects;

/** Routes execution-orchestration Artifact requests to the capability owners. */
public class DefaultArtifactManagementFeature implements ArtifactManagementFeature {

    private final ProbeConfigOperations probe;
    private final ProjectContextOperations project;
    private final PlanOperations plans;
    private final RunResultOperations runs;
    private final ExecutionExportOperations exports;

    /** Creates the non-CDE Feature from the five substantive Artifact family owners. */
    public DefaultArtifactManagementFeature(
            ProbeConfigOperations probe,
            ProjectContextOperations project,
            PlanOperations plans,
            RunResultOperations runs,
            ExecutionExportOperations exports) {
        this.probe = Objects.requireNonNull(probe, "probe operations must not be null");
        this.project = Objects.requireNonNull(project, "project operations must not be null");
        this.plans = Objects.requireNonNull(plans, "plan operations must not be null");
        this.runs = Objects.requireNonNull(runs, "run operations must not be null");
        this.exports = Objects.requireNonNull(exports, "export operations must not be null");
    }

    @Override
    public ArtifactManagementResult execute(ArtifactManagementRequest request) {
        if (request == null || request.artifactType() == null || request.action() == null) {
            return ArtifactManagementResult.blocked(
                    "artifact_management_request_invalid",
                    "artifactType and action are required",
                    Map.of("failedStep", "input_validation"));
        }
        return ArtifactManagementAction.resolve(request.artifactType(), request.action())
                .map(operation -> execute(operation, request))
                .orElseGet(() -> ArtifactManagementResult.blocked(
                        "artifact_action_not_allowed",
                        "requested action is not permitted for the Artifact family",
                        Map.of(
                                "artifactType", request.artifactType().value(),
                                "action", request.action().value(),
                                "allowedActions", ArtifactManagementAction.allowedActions(request.artifactType()))));
    }

    private ArtifactManagementResult execute(
            ArtifactManagementAction operation, ArtifactManagementRequest request) {
        return switch (operation) {
            case PROBE_CONFIG_READ -> probe.read(request);
            case PROBE_CONFIG_VALIDATE -> probe.validate(request);
            case PROBE_CONFIG_UPSERT -> probe.upsert(request);
            case PROBE_CONFIG_RELOAD -> probe.reload(request);
            case PROJECT_CONTEXT_READ -> project.read(request);
            case PROJECT_CONTEXT_VALIDATE -> project.validate(request);
            case PROJECT_CONTEXT_UPSERT -> project.upsert(request);
            case PROJECT_CONTEXT_LIST -> project.list(request);
            case PERFORMANCE_PLAN_READ -> plans.read(request, "performance");
            case PERFORMANCE_PLAN_VALIDATE -> plans.validate(request, "performance");
            case PERFORMANCE_PLAN_UPSERT -> plans.upsert(request, "performance");
            case PERFORMANCE_PLAN_LIST -> plans.list(request, "performance");
            case REGRESSION_PLAN_READ -> plans.read(request, "regression");
            case REGRESSION_PLAN_VALIDATE -> plans.validate(request, "regression");
            case REGRESSION_PLAN_UPSERT -> plans.upsert(request, "regression");
            case REGRESSION_PLAN_LIST -> plans.list(request, "regression");
            case SECURITY_PLAN_READ -> plans.read(request, "security");
            case SECURITY_PLAN_VALIDATE -> plans.validate(request, "security");
            case SECURITY_PLAN_UPSERT -> plans.upsert(request, "security");
            case SECURITY_PLAN_LIST -> plans.list(request, "security");
            case RUN_RESULT_READ -> runs.read(request);
            case RUN_RESULT_UPSERT -> runs.upsert(request);
            case RUN_RESULT_LIST -> runs.list(request);
            case RUN_RESULT_REBUILD -> runs.rebuild(request);
            case RUN_RESULT_BACKFILL -> runs.backfill(request);
            case RUN_RESULT_CUTOVER -> runs.cutover(request);
            case RUN_RESULT_QUERY -> runs.query(request);
            case RUN_RESULT_CLEANUP -> runs.cleanup(request);
            case EXECUTION_EXPORT_READ -> exports.read(request);
            case EXECUTION_EXPORT_LIST -> exports.list(request);
            case EXECUTION_EXPORT_GENERATE -> exports.generate(request);
        };
    }
}
