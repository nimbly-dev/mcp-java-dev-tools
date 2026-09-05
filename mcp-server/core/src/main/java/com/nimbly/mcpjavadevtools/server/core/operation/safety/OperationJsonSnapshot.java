package com.nimbly.mcpjavadevtools.server.core.operation.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.util.Objects;

/** Creates a bounded JSON snapshot only after encoded-size acceptance. */
public class OperationJsonSnapshot {

    private OperationJsonSnapshot() {
    }

    /** Returns a bounded parsed snapshot, or null when the encoded value exceeds the ceiling. */
    public static JsonNode copy(ObjectMapper mapper, JsonNode value, int maximumBytes)
            throws IOException {
        Objects.requireNonNull(mapper, "mapper must not be null");
        Objects.requireNonNull(value, "value must not be null");
        OperationJsonByteBuffer buffer = new OperationJsonByteBuffer(maximumBytes);
        try {
            mapper.writeValue(buffer, value);
            return parse(mapper, buffer.bytes());
        } catch (IOException exception) {
            if (buffer.exceeded()) {
                return null;
            }
            throw exception;
        } catch (StackOverflowError error) {
            return null;
        }
    }

    /** Parses already capped JSON while retaining exact decimal values. */
    public static JsonNode parse(ObjectMapper mapper, byte[] encoded) throws IOException {
        Objects.requireNonNull(mapper, "mapper must not be null");
        Objects.requireNonNull(encoded, "encoded must not be null");
        return mapper.reader()
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonNodeFactory.withExactBigDecimals(true))
                .readTree(encoded);
    }
}
