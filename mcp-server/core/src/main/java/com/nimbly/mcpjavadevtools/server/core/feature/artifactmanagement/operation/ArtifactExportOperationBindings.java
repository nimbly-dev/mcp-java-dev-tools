package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationTraceMetadata;
import java.util.List;

/** Explicit catalog rows for the Execution Export Artifact family. */
final class ArtifactExportOperationBindings {

    private ArtifactExportOperationBindings() {
    }

    static List<ArtifactOperation> create(
            ExecutionExportOperations owner, OperationTraceMetadata trace) {
        return List.of(
                new ArtifactOperation(ArtifactManagementAction.EXECUTION_EXPORT_READ,
                        ExecutionExportOperations.class, owner, "read", owner::read, trace),
                new ArtifactOperation(ArtifactManagementAction.EXECUTION_EXPORT_LIST,
                        ExecutionExportOperations.class, owner, "list", owner::list, trace),
                new ArtifactOperation(ArtifactManagementAction.EXECUTION_EXPORT_GENERATE,
                        ExecutionExportOperations.class, owner, "generate", owner::generate, trace));
    }
}
