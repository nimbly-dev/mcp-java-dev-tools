package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import java.io.IOException;
import java.io.OutputStream;

/** Request decoder that can round-trip its typed value through a bounded JSON stream. */
public interface BoundedOperationRequestDecoder<I> extends OperationRequestDecoder<I> {

    /** Writes the decoded request without first materializing a JSON tree. */
    void write(I request, OutputStream output) throws IOException;
}
