package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Reusable bounded request decoder adapters for explicit operation registrations. */
public class OperationRequestDecoders {

    private OperationRequestDecoders() {
    }

    /** Creates a mapper-backed decoder with bounded Java default-container handling. */
    public static <I> BoundedOperationRequestDecoder<I> typed(
            ObjectMapper mapper, Class<I> requestType) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        Objects.requireNonNull(requestType, "requestType must not be null");
        return new OperationRequestDecoderAdapter<>(
                mapper, requestType, input -> mapper.convertValue(input, requestType), null);
    }

    /**
     * Creates a typed decoder with Java-owned defaults. Keys are top-level JSON field names;
     * nested defaults use RFC 6901 JSON Pointer keys such as {@code /options/mode}; a leading
     * slash is reserved for pointer keys.
     */
    public static <I> BoundedOperationRequestDecoder<I> typedWithDefaults(
            ObjectMapper mapper, Class<I> requestType, Map<String, JsonNode> defaultFields) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        Objects.requireNonNull(requestType, "requestType must not be null");
        Objects.requireNonNull(defaultFields, "defaultFields must not be null");
        return new OperationRequestDecoderAdapter<>(
                mapper, requestType, input -> mapper.convertValue(input, requestType), null,
                defaultFields);
    }

    /** Adapts custom decode behavior while retaining bounded round-trip validation. */
    public static <I> BoundedOperationRequestDecoder<I> wrap(
            ObjectMapper mapper, Class<I> requestType, OperationRequestDecoder<I> decoder) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        Objects.requireNonNull(requestType, "requestType must not be null");
        Objects.requireNonNull(decoder, "decoder must not be null");
        return new OperationRequestDecoderAdapter<>(mapper, requestType, decoder, null);
    }

    /** Adapts a decoder whose wire representation is intentionally a JSON projection. */
    public static <I> BoundedOperationRequestDecoder<I> wrap(
            ObjectMapper mapper,
            Class<I> requestType,
            OperationRequestDecoder<I> decoder,
            Function<I, JsonNode> wireValue) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        Objects.requireNonNull(requestType, "requestType must not be null");
        Objects.requireNonNull(decoder, "decoder must not be null");
        Objects.requireNonNull(wireValue, "wireValue must not be null");
        return new OperationRequestDecoderAdapter<>(mapper, requestType, decoder, wireValue);
    }
}
