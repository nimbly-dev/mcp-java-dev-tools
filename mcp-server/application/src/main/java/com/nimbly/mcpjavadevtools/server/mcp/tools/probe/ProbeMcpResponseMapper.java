package com.nimbly.mcpjavadevtools.server.mcp.tools.probe;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResultPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.response.ProbeDeterministicOutputMapper;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailure;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponseMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts Core-owned Probe output into the Spring AI MCP response shape.
 */
public class ProbeMcpResponseMapper implements McpActionResponseMapper<ProbeRequest, ProbeResult> {

    private final ProbeDeterministicOutputMapper outputMapper;

    /** Creates the transport mapper with the Core-owned output mapper. */
    public ProbeMcpResponseMapper() {
        this(new ProbeDeterministicOutputMapper());
    }

    ProbeMcpResponseMapper(ProbeDeterministicOutputMapper outputMapper) {
        this.outputMapper = outputMapper;
    }

    /**
     * Converts one Core outcome without adding Probe policy.
     *
     * @param request typed Core request
     * @param result Core outcome
     * @return transport-safe MCP response
     */
    public McpActionResponse map(ProbeRequest request, ProbeResult result) {
        ProbeResultPolicy policy = result.policy();
        return new McpActionResponse(
                "report",
                policy.status(),
                result.reasonCode().value(),
                policy.nextActionCode(),
                policy.nextAction(),
                "",
                metadata(result.reasonMetadata()),
                result.actionResult().orElse(null),
                outputMapper.map(request, result));
    }

    /**
     * Maps an outcome when no typed request is available.
     *
     * @param result Core outcome
     * @return transport-safe MCP response
     */
    public McpActionResponse map(ProbeResult result) {
        return map(null, result);
    }

    /**
     * Creates the canonical invalid-request transport response.
     *
     * @return deterministic invalid request response
     */
    public McpActionResponse invalidRequest() {
        return map(ProbeResult.failure(ProbeReasonCode.INVALID_REQUEST, ProbeReasonMetadata.inputValidation()));
    }

    /**
     * Converts a neutral Application boundary failure into the Probe response envelope.
     *
     * @param failure sanitized boundary failure
     * @return deterministic Probe internal-error response
     */
    public McpActionResponse mapBoundary(McpBoundaryFailure failure) {
        return new McpActionResponse(
                "report",
                "internal_error",
                "internal_error",
                "internal_error",
                "Retry the request. If the error persists, provide the correlationId to an operator.",
                "An internal MCP boundary error occurred.",
                Map.of(
                        "correlationId", failure.correlationId(),
                        "failedStep", failure.failureKind().value()),
                null,
                Map.of());
    }

    private static Map<String, Object> metadata(ProbeReasonMetadata metadata) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (metadata.failedStep() != null) {
            values.put("failedStep", metadata.failedStep().value());
        }
        if (metadata.probeId() != null) {
            values.put("probeId", metadata.probeId());
        }
        if (metadata.probeCount() != null) {
            values.put("probeCount", metadata.probeCount());
        }
        return Map.copyOf(values);
    }
}
