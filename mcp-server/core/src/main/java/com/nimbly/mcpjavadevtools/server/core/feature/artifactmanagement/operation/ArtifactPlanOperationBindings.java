package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import java.util.List;

/** Explicit catalog rows for the Performance, Regression, and Security plan families. */
final class ArtifactPlanOperationBindings {

    private ArtifactPlanOperationBindings() {
    }

    static List<ArtifactOperation> create(
            PlanOperations owner, OperationTraceMetadata trace) {
        return List.of(
                new ArtifactOperation(ArtifactManagementAction.PERFORMANCE_PLAN_READ,
                        PlanOperations.class, owner, "read(performance)",
                        request -> owner.read(request, "performance"), trace),
                new ArtifactOperation(ArtifactManagementAction.PERFORMANCE_PLAN_VALIDATE,
                        PlanOperations.class, owner, "validate(performance)",
                        request -> owner.validate(request, "performance"), trace),
                new ArtifactOperation(ArtifactManagementAction.PERFORMANCE_PLAN_UPSERT,
                        PlanOperations.class, owner, "upsert(performance)",
                        request -> owner.upsert(request, "performance"), trace),
                new ArtifactOperation(ArtifactManagementAction.PERFORMANCE_PLAN_LIST,
                        PlanOperations.class, owner, "list(performance)",
                        request -> owner.list(request, "performance"), trace),
                new ArtifactOperation(ArtifactManagementAction.REGRESSION_PLAN_READ,
                        PlanOperations.class, owner, "read(regression)",
                        request -> owner.read(request, "regression"), trace),
                new ArtifactOperation(ArtifactManagementAction.REGRESSION_PLAN_VALIDATE,
                        PlanOperations.class, owner, "validate(regression)",
                        request -> owner.validate(request, "regression"), trace),
                new ArtifactOperation(ArtifactManagementAction.REGRESSION_PLAN_UPSERT,
                        PlanOperations.class, owner, "upsert(regression)",
                        request -> owner.upsert(request, "regression"), trace),
                new ArtifactOperation(ArtifactManagementAction.REGRESSION_PLAN_LIST,
                        PlanOperations.class, owner, "list(regression)",
                        request -> owner.list(request, "regression"), trace),
                new ArtifactOperation(ArtifactManagementAction.SECURITY_PLAN_READ,
                        PlanOperations.class, owner, "read(security)",
                        request -> owner.read(request, "security"), trace),
                new ArtifactOperation(ArtifactManagementAction.SECURITY_PLAN_VALIDATE,
                        PlanOperations.class, owner, "validate(security)",
                        request -> owner.validate(request, "security"), trace),
                new ArtifactOperation(ArtifactManagementAction.SECURITY_PLAN_UPSERT,
                        PlanOperations.class, owner, "upsert(security)",
                        request -> owner.upsert(request, "security"), trace),
                new ArtifactOperation(ArtifactManagementAction.SECURITY_PLAN_LIST,
                        PlanOperations.class, owner, "list(security)",
                        request -> owner.list(request, "security"), trace));
    }
}
