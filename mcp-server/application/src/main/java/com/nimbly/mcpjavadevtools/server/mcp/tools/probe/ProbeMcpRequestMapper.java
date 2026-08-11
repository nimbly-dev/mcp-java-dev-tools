package com.nimbly.mcpjavadevtools.server.mcp.tools.probe;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequestFactory;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequestInput;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequest;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequestMapper;
import java.util.Map;

/**
 * Converts MCP transport fields into the Core request-factory input contract.
 */
public class ProbeMcpRequestMapper implements McpActionRequestMapper<ProbeMcpActionInput, ProbeRequest> {

    private final ProbeRequestFactory requestFactory;

    /** Creates the transport mapper with the Core-owned request factory. */
    public ProbeMcpRequestMapper() {
        this(new ProbeRequestFactory());
    }

    ProbeMcpRequestMapper(ProbeRequestFactory requestFactory) {
        this.requestFactory = requestFactory;
    }

    /**
     * Converts one MCP request without applying capability-specific policy.
     *
     * @param request public MCP request
     * @return typed Core request
     */
    public ProbeRequest map(McpActionRequest<ProbeMcpActionInput> request) {
        if (request == null || request.input() == null) {
            throw new IllegalArgumentException("Probe MCP request requires action and input");
        }
        ProbeAction action = ProbeAction.fromValue(request.action())
                .orElseThrow(() -> new IllegalArgumentException("Probe MCP action is not supported"));
        return requestFactory.create(action, input(request.input()));
    }

    private static ProbeRequestInput input(ProbeMcpActionInput input) {
        Map<String, String> headers = input.http() == null || input.http().headers() == null
                ? Map.of()
                : input.http().headers();
        return new ProbeRequestInput(
                input.baseUrl(),
                input.probeId(),
                headers,
                input.timeoutMs(),
                input.key(),
                input.keys(),
                input.lineHint(),
                input.className(),
                input.pollIntervalMs(),
                input.maxRetries(),
                input.captureId(),
                input.action(),
                input.sessionId(),
                input.actuatorId(),
                input.targetKey(),
                input.returnBoolean(),
                input.ttlMs(),
                input.provider(),
                input.event(),
                input.intervalNanos(),
                input.outputPath(),
                input.outputFormat());
    }
}
