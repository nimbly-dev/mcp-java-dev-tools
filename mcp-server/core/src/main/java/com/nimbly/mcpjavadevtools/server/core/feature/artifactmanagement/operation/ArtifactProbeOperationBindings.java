package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import java.util.List;

/** Explicit catalog rows for the Probe Config Artifact family. */
final class ArtifactProbeOperationBindings {

    private ArtifactProbeOperationBindings() {
    }

    static List<ArtifactOperation> create(
            ProbeConfigOperations owner, OperationTraceMetadata trace) {
        return List.of(
                new ArtifactOperation(ArtifactManagementAction.PROBE_CONFIG_READ,
                        ProbeConfigOperations.class, owner, "read", owner::read, trace),
                new ArtifactOperation(ArtifactManagementAction.PROBE_CONFIG_VALIDATE,
                        ProbeConfigOperations.class, owner, "validate", owner::validate, trace),
                new ArtifactOperation(ArtifactManagementAction.PROBE_CONFIG_UPSERT,
                        ProbeConfigOperations.class, owner, "upsert", owner::upsert, trace),
                new ArtifactOperation(ArtifactManagementAction.PROBE_CONFIG_RELOAD,
                        ProbeConfigOperations.class, owner, "reload", owner::reload, trace));
    }
}
