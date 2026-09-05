package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/** Mapper-backed result encoder that supports bounded streaming before tree materialization. */
public class MapperOperationResultEncoder<O> implements BoundedOperationResultEncoder<O> {

    private final ObjectMapper mapper;
    private final Class<O> resultType;

    MapperOperationResultEncoder(ObjectMapper mapper, Class<O> resultType) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.resultType = Objects.requireNonNull(resultType, "resultType must not be null");
    }

    @Override
    public JsonNode encode(O result) {
        return mapper.valueToTree(resultType.cast(result));
    }

    @Override
    public void write(O result, OutputStream output) throws IOException {
        JsonGenerator generator = mapper.getFactory().createGenerator(output);
        generator.disable(JsonGenerator.Feature.QUOTE_NON_NUMERIC_NUMBERS);
        try {
            mapper.writeValue(generator, resultType.cast(result));
        } finally {
            generator.close();
        }
    }
}
