package com.nimbly.mcpjavadevtools.server.mcp.tools.artifactmanagement;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailure;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import java.util.Map;

/** Normalizes Core Deterministic Output into the shared MCP response envelope. */
public final class ArtifactManagementMcpResponseMapper {

    /** Maps one Core result without adding capability behavior. */
    public McpActionResponse map(ArtifactManagementResult result) {
        return new McpActionResponse(
                result.resultType(),
                result.status(),
                result.reasonCode(),
                result.nextActionCode(),
                result.nextAction(),
                result.reason(),
                result.reasonMeta(),
                null,
                result.details());
    }

    /** Creates the deterministic invalid-request response. */
    public McpActionResponse invalidRequest() {
        return map(ArtifactManagementResult.blocked(
                "artifact_management_request_invalid",
                "artifactType, action, and input are required",
                Map.of("failedStep", "input_validation")));
    }

    /** Maps an unexpected Application boundary failure without leaking details. */
    public McpActionResponse mapBoundary(McpBoundaryFailure failure) {
        return new McpActionResponse(
                "report",
                "internal_error",
                "internal_error",
                "internal_error",
                "Retry the request. If the error persists, provide the correlationId to an operator.",
                "An internal MCP boundary error occurred.",
                Map.of("correlationId", failure.correlationId(), "failedStep", failure.failureKind().value()),
                null,
                Map.of());
    }
}
