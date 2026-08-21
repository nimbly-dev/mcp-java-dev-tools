package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.grpc;

import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;

/** Recognizes gRPC while remaining fail-closed until a dedicated provider story exists. */
public final class GrpcTransportProvider implements TransportProvider {

    @Override
    public TransportProtocol protocol() {
        return TransportProtocol.GRPC;
    }

    @Override
    public ExecuteTransportResult execute(ExecuteTransportRequest request) {
        return ExecuteTransportResult.unsupported(protocol().value());
    }
}
