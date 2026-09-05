package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

/** Reusable bounded encoders for mapper-backed typed operation results. */
public class OperationResultEncoders {

    private OperationResultEncoders() {
    }

    /** Creates an encoder whose mapper stream supplies the bounded JSON result. */
    public static <O> BoundedOperationResultEncoder<O> typed(
            ObjectMapper mapper, Class<O> resultType) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        Objects.requireNonNull(resultType, "resultType must not be null");
        return new MapperOperationResultEncoder<>(mapper, resultType);
    }
}
