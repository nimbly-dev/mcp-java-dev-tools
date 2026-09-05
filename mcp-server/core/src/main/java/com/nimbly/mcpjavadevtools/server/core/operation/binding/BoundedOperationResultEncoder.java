package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import java.io.IOException;
import java.io.OutputStream;

/** Result encoder whose stream is the authoritative bounded JSON representation. */
public interface BoundedOperationResultEncoder<O> extends OperationResultEncoder<O> {

    /** Writes the typed result without first materializing a JSON tree. */
    void write(O result, OutputStream output) throws IOException;
}
