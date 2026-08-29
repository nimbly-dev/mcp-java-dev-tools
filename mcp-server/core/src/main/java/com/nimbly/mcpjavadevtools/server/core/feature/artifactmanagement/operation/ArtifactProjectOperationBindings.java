package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationTraceMetadata;
import java.util.List;

/** Explicit catalog rows for the Project Context Artifact family. */
final class ArtifactProjectOperationBindings {

    private ArtifactProjectOperationBindings() {
    }

    static List<ArtifactOperation> create(
            ProjectContextOperations owner, OperationTraceMetadata trace) {
        return List.of(
                new ArtifactOperation(ArtifactManagementAction.PROJECT_CONTEXT_READ,
                        ProjectContextOperations.class, owner, "read", owner::read, trace),
                new ArtifactOperation(ArtifactManagementAction.PROJECT_CONTEXT_VALIDATE,
                        ProjectContextOperations.class, owner, "validate", owner::validate, trace),
                new ArtifactOperation(ArtifactManagementAction.PROJECT_CONTEXT_UPSERT,
                        ProjectContextOperations.class, owner, "upsert", owner::upsert, trace),
                new ArtifactOperation(ArtifactManagementAction.PROJECT_CONTEXT_LIST,
                        ProjectContextOperations.class, owner, "list", owner::list, trace));
    }
}
