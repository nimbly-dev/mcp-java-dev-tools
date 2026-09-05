package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import com.fasterxml.jackson.databind.JsonNode;

/** Explicit boundary mapper from one typed Core result to aggregate JSON. */
@FunctionalInterface
public interface OperationResultEncoder<O> {

    /** @param result typed result @return JSON result for direct compatibility callers */
    JsonNode encode(O result);
}
