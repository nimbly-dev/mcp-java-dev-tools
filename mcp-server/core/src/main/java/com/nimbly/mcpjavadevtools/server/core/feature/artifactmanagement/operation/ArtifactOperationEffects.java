package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;

/** Classifies the externally visible effect of an Artifact operation. */
final class ArtifactOperationEffects {

    private ArtifactOperationEffects() {
    }

    static String sideEffect(ArtifactManagementAction action) {
        return switch (action.action()) {
            case READ, VALIDATE, LIST -> "filesystem_read";
            case UPSERT -> "filesystem_write";
            case RELOAD -> "probe_registry_reload";
            case GENERATE -> "filesystem_export";
            case REBUILD, BACKFILL, CUTOVER, CLEANUP -> "sqlite_write";
            case QUERY -> "sqlite_read";
        };
    }
}
