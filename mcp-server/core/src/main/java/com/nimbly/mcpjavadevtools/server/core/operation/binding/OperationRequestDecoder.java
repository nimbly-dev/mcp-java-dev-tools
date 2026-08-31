package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import com.fasterxml.jackson.databind.JsonNode;

/** Explicit boundary mapper from aggregate JSON input to one typed Core request. */
@FunctionalInterface
public interface OperationRequestDecoder<I> {

    /** @param input validated JSON input @return typed request */
    I decode(JsonNode input);
}
