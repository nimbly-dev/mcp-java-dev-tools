package com.nimbly.mcpjavadevtools.server.mcp.tools.transportexecute;

import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;

/** Maps the unchanged public MCP shape into the Core action request. */
final class TransportExecuteMcpRequestMapper {

    ExecuteTransportRequest map(TransportExecuteMcpInput input) {
        if (input == null || input.request() == null) {
            throw new IllegalArgumentException("transport_execute requires protocol and request");
        }
        TransportProtocol protocol = TransportProtocol.fromValue(input.protocol())
                .orElseThrow(() -> new IllegalArgumentException("transport_execute protocol is unsupported"));
        boolean wrappedOnly = input.options() == null
                || input.options().wrappedOnly() == null
                || input.options().wrappedOnly();
        return new ExecuteTransportRequest(protocol, input.request(), wrappedOnly);
    }
}
