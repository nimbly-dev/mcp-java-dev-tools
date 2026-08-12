package com.nimbly.mcpjavadevtools.server.mcp.tools.jvmlifecycle;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleInput;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequestFactory;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequest;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequestMapper;

/**
 * Maps the proven Spring AI action/input shape into Core-owned request models.
 */
public final class JvmLifecycleMcpRequestMapper
        implements McpActionRequestMapper<JvmLifecycleMcpActionInput, JvmLifecycleRequest> {

    private final JvmLifecycleRequestFactory requestFactory;

    /** Creates the mapper with the Core-owned request factory. */
    public JvmLifecycleMcpRequestMapper() {
        this(new JvmLifecycleRequestFactory());
    }

    JvmLifecycleMcpRequestMapper(JvmLifecycleRequestFactory requestFactory) {
        this.requestFactory = requestFactory;
    }

    @Override
    public JvmLifecycleRequest map(McpActionRequest<JvmLifecycleMcpActionInput> request) {
        if (request == null || request.input() == null) {
            throw new IllegalArgumentException("JVM lifecycle request requires action and input");
        }
        JvmLifecycleAction action = JvmLifecycleAction.fromValue(request.action())
                .orElseThrow(() -> new IllegalArgumentException("JVM lifecycle action is unsupported"));
        JvmLifecycleMcpActionInput input = request.input();
        return requestFactory.create(action, new JvmLifecycleInput(
                input.pid(),
                input.expectedProcessStartEpochMs(),
                input.confirm(),
                input.probeHost(),
                input.probePort(),
                input.include(),
                input.exclude()));
    }
}
