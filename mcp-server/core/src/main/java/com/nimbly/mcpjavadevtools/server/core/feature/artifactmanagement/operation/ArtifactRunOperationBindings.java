package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.RunResultOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationTraceMetadata;
import java.util.List;

/** Explicit catalog rows for the Run Result Artifact family. */
final class ArtifactRunOperationBindings {

    private ArtifactRunOperationBindings() {
    }

    static List<ArtifactOperation> create(
            RunResultOperations owner, OperationTraceMetadata trace) {
        return List.of(
                new ArtifactOperation(ArtifactManagementAction.RUN_RESULT_READ,
                        RunResultOperations.class, owner, "read", owner::read, trace),
                new ArtifactOperation(ArtifactManagementAction.RUN_RESULT_UPSERT,
                        RunResultOperations.class, owner, "upsert", owner::upsert, trace),
                new ArtifactOperation(ArtifactManagementAction.RUN_RESULT_LIST,
                        RunResultOperations.class, owner, "list", owner::list, trace),
                new ArtifactOperation(ArtifactManagementAction.RUN_RESULT_REBUILD,
                        RunResultOperations.class, owner, "rebuild", owner::rebuild, trace),
                new ArtifactOperation(ArtifactManagementAction.RUN_RESULT_BACKFILL,
                        RunResultOperations.class, owner, "backfill", owner::backfill, trace),
                new ArtifactOperation(ArtifactManagementAction.RUN_RESULT_CUTOVER,
                        RunResultOperations.class, owner, "cutover", owner::cutover, trace),
                new ArtifactOperation(ArtifactManagementAction.RUN_RESULT_QUERY,
                        RunResultOperations.class, owner, "query", owner::query, trace),
                new ArtifactOperation(ArtifactManagementAction.RUN_RESULT_CLEANUP,
                        RunResultOperations.class, owner, "cleanup", owner::cleanup, trace));
    }
}
