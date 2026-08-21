package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.custom;

import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;

/** Recognizes custom transport while remaining fail-closed until a provider story exists. */
public final class CustomTransportProvider implements TransportProvider {

    @Override
    public TransportProtocol protocol() {
        return TransportProtocol.CUSTOM;
    }

    @Override
    public ExecuteTransportResult execute(ExecuteTransportRequest request) {
        return ExecuteTransportResult.unsupported(protocol().value());
    }
}
