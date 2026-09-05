package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationJsonSnapshot;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationJsonSize;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyLimits;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationJsonTreeLimits;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Package-owned bounded decoder adapter for typed and projected request values. */
public class OperationRequestDecoderAdapter<I> implements BoundedOperationRequestDecoder<I> {

    private final ObjectMapper mapper;
    private final Class<I> requestType;
    private final OperationRequestDecoder<I> decoder;
    private final Function<I, JsonNode> wireValue;
    private final Map<String, JsonNode> defaultFields;

    OperationRequestDecoderAdapter(
            ObjectMapper mapper,
            Class<I> requestType,
            OperationRequestDecoder<I> decoder,
            Function<I, JsonNode> wireValue) {
        this(mapper, requestType, decoder, wireValue, Map.of());
    }

    OperationRequestDecoderAdapter(
            ObjectMapper mapper,
            Class<I> requestType,
            OperationRequestDecoder<I> decoder,
            Function<I, JsonNode> wireValue,
            Map<String, JsonNode> defaultFields) {
        this.mapper = mapper;
        this.requestType = requestType;
        this.decoder = decoder;
        this.wireValue = wireValue;
        this.defaultFields = Collections.unmodifiableMap(boundedDefaults(mapper, defaultFields));
    }

    @Override
    public I decode(JsonNode input) {
        return decoder.decode(input);
    }

    @Override
    public void write(I request, OutputStream output) throws IOException {
        if (wireValue == null) {
            mapper.writeValue(output, requestType.cast(request));
        } else {
            mapper.writeValue(output, wireValue.apply(request));
        }
    }

    Map<String, JsonNode> defaultFields() {
        return defaultFields;
    }

    static Map<String, JsonNode> boundedDefaults(
            ObjectMapper mapper, Map<String, JsonNode> defaults) {
        if (defaults.size() > OperationSafetyLimits.MAX_JSON_NODES) {
            throw new IllegalArgumentException("operation defaults exceed the node limit");
        }
        ObjectNode declaration = mapper.createObjectNode();
        for (Map.Entry<String, JsonNode> entry : defaults.entrySet()) {
            String path = Objects.requireNonNull(entry.getKey(), "default field path must not be null");
            JsonNode value = Objects.requireNonNull(
                    entry.getValue(), "default field value must not be null");
            declaration.set(path, value);
        }
        if (!OperationJsonTreeLimits.violations(declaration).isEmpty()) {
            throw new IllegalArgumentException("operation defaults exceed JSON limits");
        }
        try {
            if (OperationJsonSize.measure(
                    mapper, declaration, OperationSafetyLimits.MAX_INPUT_BYTES) < 0) {
                throw new IllegalArgumentException("operation defaults exceed the input byte limit");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "operation defaults cannot be bounded", exception);
        }
        return snapshotDefaults(mapper, canonicalDefaults(defaults));
    }

    static Map<String, JsonNode> canonicalDefaults(Map<String, JsonNode> defaults) {
        Map<String, JsonNode> canonical = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : defaults.entrySet()) {
            String path = OperationRequestEquivalence.canonicalDefaultPath(entry.getKey());
            if (canonical.putIfAbsent(path, entry.getValue()) != null) {
                throw new IllegalArgumentException("operation default paths are ambiguous");
            }
        }
        return canonical;
    }

    static Map<String, JsonNode> snapshotDefaults(
            ObjectMapper mapper, Map<String, JsonNode> defaults) {
        Map<String, JsonNode> copies = new LinkedHashMap<>();
        int remainingBytes = OperationSafetyLimits.MAX_INPUT_BYTES;
        for (Map.Entry<String, JsonNode> entry : defaults.entrySet()) {
            if (remainingBytes < 1) {
                throw new IllegalArgumentException("operation defaults exceed the input byte limit");
            }
            try {
                int encodedBytes = OperationJsonSize.measure(mapper, entry.getValue(), remainingBytes);
                if (encodedBytes < 0) {
                    throw new IllegalArgumentException(
                            "operation defaults exceed the input byte limit");
                }
                JsonNode snapshot = OperationJsonSnapshot.copy(mapper, entry.getValue(), remainingBytes);
                if (snapshot == null) {
                    throw new IllegalArgumentException(
                            "operation defaults exceed the input byte limit");
                }
                copies.put(entry.getKey(), snapshot);
                remainingBytes -= encodedBytes;
            } catch (IOException | RuntimeException exception) {
                if (exception instanceof IllegalArgumentException illegalArgument) {
                    throw illegalArgument;
                }
                throw new IllegalArgumentException(
                        "operation default value cannot be bounded", exception);
            }
        }
        return copies;
    }
}
