package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Complete allowlist of Artifact family/action pairs. */
public enum ArtifactManagementAction {
    PROBE_CONFIG_READ(ArtifactType.PROBE_CONFIG, ArtifactAction.READ),
    PROBE_CONFIG_VALIDATE(ArtifactType.PROBE_CONFIG, ArtifactAction.VALIDATE),
    PROBE_CONFIG_UPSERT(ArtifactType.PROBE_CONFIG, ArtifactAction.UPSERT),
    PROBE_CONFIG_RELOAD(ArtifactType.PROBE_CONFIG, ArtifactAction.RELOAD),
    PROJECT_CONTEXT_READ(ArtifactType.PROJECT_CONTEXT, ArtifactAction.READ),
    PROJECT_CONTEXT_VALIDATE(ArtifactType.PROJECT_CONTEXT, ArtifactAction.VALIDATE),
    PROJECT_CONTEXT_UPSERT(ArtifactType.PROJECT_CONTEXT, ArtifactAction.UPSERT),
    PROJECT_CONTEXT_LIST(ArtifactType.PROJECT_CONTEXT, ArtifactAction.LIST),
    PERFORMANCE_PLAN_READ(ArtifactType.PERFORMANCE_PLAN, ArtifactAction.READ),
    PERFORMANCE_PLAN_VALIDATE(ArtifactType.PERFORMANCE_PLAN, ArtifactAction.VALIDATE),
    PERFORMANCE_PLAN_UPSERT(ArtifactType.PERFORMANCE_PLAN, ArtifactAction.UPSERT),
    PERFORMANCE_PLAN_LIST(ArtifactType.PERFORMANCE_PLAN, ArtifactAction.LIST),
    REGRESSION_PLAN_READ(ArtifactType.REGRESSION_PLAN, ArtifactAction.READ),
    REGRESSION_PLAN_VALIDATE(ArtifactType.REGRESSION_PLAN, ArtifactAction.VALIDATE),
    REGRESSION_PLAN_UPSERT(ArtifactType.REGRESSION_PLAN, ArtifactAction.UPSERT),
    REGRESSION_PLAN_LIST(ArtifactType.REGRESSION_PLAN, ArtifactAction.LIST),
    SECURITY_PLAN_READ(ArtifactType.SECURITY_PLAN, ArtifactAction.READ),
    SECURITY_PLAN_VALIDATE(ArtifactType.SECURITY_PLAN, ArtifactAction.VALIDATE),
    SECURITY_PLAN_UPSERT(ArtifactType.SECURITY_PLAN, ArtifactAction.UPSERT),
    SECURITY_PLAN_LIST(ArtifactType.SECURITY_PLAN, ArtifactAction.LIST),
    RUN_RESULT_READ(ArtifactType.RUN_RESULT, ArtifactAction.READ),
    RUN_RESULT_LIST(ArtifactType.RUN_RESULT, ArtifactAction.LIST),
    RUN_RESULT_REBUILD(ArtifactType.RUN_RESULT, ArtifactAction.REBUILD),
    RUN_RESULT_BACKFILL(ArtifactType.RUN_RESULT, ArtifactAction.BACKFILL),
    RUN_RESULT_CUTOVER(ArtifactType.RUN_RESULT, ArtifactAction.CUTOVER),
    RUN_RESULT_QUERY(ArtifactType.RUN_RESULT, ArtifactAction.QUERY),
    RUN_RESULT_CLEANUP(ArtifactType.RUN_RESULT, ArtifactAction.CLEANUP),
    EXECUTION_EXPORT_READ(ArtifactType.EXECUTION_EXPORT, ArtifactAction.READ),
    EXECUTION_EXPORT_LIST(ArtifactType.EXECUTION_EXPORT, ArtifactAction.LIST),
    EXECUTION_EXPORT_GENERATE(ArtifactType.EXECUTION_EXPORT, ArtifactAction.GENERATE);

    private final ArtifactType artifactType;
    private final ArtifactAction action;

    ArtifactManagementAction(ArtifactType artifactType, ArtifactAction action) {
        this.artifactType = artifactType;
        this.action = action;
    }

    /** @return owning Artifact family */
    public ArtifactType artifactType() {
        return artifactType;
    }

    /** @return public action */
    public ArtifactAction action() {
        return action;
    }

    /** Resolves an allowed family/action pair. */
    public static Optional<ArtifactManagementAction> resolve(
            ArtifactType artifactType,
            ArtifactAction action) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.artifactType == artifactType && candidate.action == action)
                .findFirst();
    }

    /** Returns the exact action allowlist for one Artifact family. */
    public static List<String> allowedActions(ArtifactType artifactType) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.artifactType == artifactType)
                .map(candidate -> candidate.action.value())
                .toList();
    }
}
