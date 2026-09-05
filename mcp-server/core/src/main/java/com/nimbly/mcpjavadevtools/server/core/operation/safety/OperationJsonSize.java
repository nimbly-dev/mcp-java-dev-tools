package com.nimbly.mcpjavadevtools.server.core.operation.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/** Measures JSON encoding with a hard stop before an oversized byte buffer exists. */
public class OperationJsonSize {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();

    private OperationJsonSize() {
    }

    /** Measures canonical Core JSON encoding; returns -1 when the ceiling is exceeded. */
    public static int measure(JsonNode value, int maximumBytes) {
        try {
            return measure(CANONICAL_MAPPER, value, maximumBytes);
        } catch (IOException exception) {
            throw new IllegalArgumentException("operation JSON cannot be encoded", exception);
        }
    }

    /** Measures mapper encoding; returns -1 when the ceiling is exceeded. */
    public static int measure(
            ObjectMapper mapper, JsonNode value, int maximumBytes) throws IOException {
        if (mapper == null || value == null) {
            throw new IllegalArgumentException("mapper and JSON value must not be null");
        }
        OperationJsonByteBuffer buffer = new OperationJsonByteBuffer(maximumBytes, false);
        try {
            mapper.writeValue(buffer, value);
            return buffer.size();
        } catch (IOException exception) {
            if (buffer.exceeded()) {
                return -1;
            }
            throw exception;
        }
    }
}
