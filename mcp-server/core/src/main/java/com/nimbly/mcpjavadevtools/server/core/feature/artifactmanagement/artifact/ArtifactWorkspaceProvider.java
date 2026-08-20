package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import java.nio.file.Path;
import java.util.Optional;

/** Application-owned source of the current MCP workspace root. */
public interface ArtifactWorkspaceProvider {

    /** @return the currently bound workspace root, when one is available */
    Optional<Path> currentWorkspaceRoot();
}
