package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable, normalized bound workspace snapshot. */
public record RouteSynthesisWorkspaceSnapshot(Path workspaceRoot) {

    /** Normalizes and validates the workspace root. */
    public RouteSynthesisWorkspaceSnapshot {
        workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null")
                .toAbsolutePath()
                .normalize();
    }
}
