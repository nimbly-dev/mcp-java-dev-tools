package com.nimbly.mcpjavadevtools.server.mcp.tools.artifactmanagement;

import java.util.Map;

/** Transport-only Artifact Management request envelope. */
public record ArtifactManagementMcpRequest(
        String artifactType,
        String action,
        Map<String, Object> input) {
}
