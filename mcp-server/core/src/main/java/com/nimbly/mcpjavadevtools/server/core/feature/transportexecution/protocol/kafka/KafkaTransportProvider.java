package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.kafka;

import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;

/** Recognizes Kafka while remaining fail-closed until a dedicated provider story exists. */
public final class KafkaTransportProvider implements TransportProvider {

    @Override
    public TransportProtocol protocol() {
        return TransportProtocol.KAFKA;
    }

    @Override
    public ExecuteTransportResult execute(ExecuteTransportRequest request) {
        return ExecuteTransportResult.unsupported(protocol().value());
    }
}
