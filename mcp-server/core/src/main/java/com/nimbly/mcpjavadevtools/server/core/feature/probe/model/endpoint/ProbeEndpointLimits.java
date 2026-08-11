package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configured hard limits for action-neutral Probe endpoint transport values.
 *
 * @param maximumHeaderCount maximum request or response header count
 * @param maximumHeaderNameBytes maximum UTF-8 header-name size
 * @param maximumHeaderValueBytes maximum UTF-8 header-value size
 * @param maximumRequestPayloadBytes maximum UTF-8 request payload size
 * @param maximumResponsePayloadBytes maximum UTF-8 response payload size
 */
public record ProbeEndpointLimits(
        int maximumHeaderCount,
        int maximumHeaderNameBytes,
        int maximumHeaderValueBytes,
        int maximumRequestPayloadBytes,
        int maximumResponsePayloadBytes) {

    private static final int HARD_MAXIMUM_HEADER_COUNT = 64;
    private static final int HARD_MAXIMUM_HEADER_NAME_BYTES = 128;
    private static final int HARD_MAXIMUM_HEADER_VALUE_BYTES = 4_096;
    private static final int HARD_MAXIMUM_REQUEST_PAYLOAD_BYTES = 65_536;
    private static final int HARD_MAXIMUM_RESPONSE_PAYLOAD_BYTES = 1_048_576;

    /**
     * Validates positive configured limits within Core-owned resource ceilings.
     */
    public ProbeEndpointLimits {
        validateLimit(maximumHeaderCount, HARD_MAXIMUM_HEADER_COUNT, "maximumHeaderCount");
        validateLimit(maximumHeaderNameBytes, HARD_MAXIMUM_HEADER_NAME_BYTES, "maximumHeaderNameBytes");
        validateLimit(maximumHeaderValueBytes, HARD_MAXIMUM_HEADER_VALUE_BYTES, "maximumHeaderValueBytes");
        validateLimit(
                maximumRequestPayloadBytes,
                HARD_MAXIMUM_REQUEST_PAYLOAD_BYTES,
                "maximumRequestPayloadBytes");
        validateLimit(
                maximumResponsePayloadBytes,
                HARD_MAXIMUM_RESPONSE_PAYLOAD_BYTES,
                "maximumResponsePayloadBytes");
    }

    /**
     * Copies HTTP headers after security and size validation.
     *
     * @param headers request or response headers
     * @return bounded immutable header map
     */
    public Map<String, String> copyHeaders(Map<String, String> headers) {
        if (headers == null || headers.size() > maximumHeaderCount) {
            throw new IllegalArgumentException("headers exceed the configured limit");
        }
        Map<String, String> validatedHeaders = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            addHeader(validatedHeaders, header);
        }
        return Map.copyOf(validatedHeaders);
    }

    /**
     * Rejects a request payload outside the configured transport limit.
     *
     * @param payload request payload
     * @return non-null bounded request payload
     */
    public String requestPayload(String payload) {
        return boundedPayload(payload, maximumRequestPayloadBytes, "request payload");
    }

    /**
     * Rejects a response payload outside the configured transport limit.
     *
     * @param payload response payload
     * @return non-null bounded response payload
     */
    public String responsePayload(String payload) {
        return boundedPayload(payload, maximumResponsePayloadBytes, "response payload");
    }

    /**
     * Rejects binary response content outside the configured transport limit.
     *
     * @param payload response bytes
     * @return defensive bounded copy of response bytes
     */
    public byte[] responsePayloadBytes(byte[] payload) {
        byte[] normalized = payload == null ? new byte[0] : payload.clone();
        if (normalized.length > maximumResponsePayloadBytes) {
            throw new IllegalArgumentException("response payload exceeds the configured maximum size");
        }
        return normalized;
    }

    private void addHeader(Map<String, String> headers, Map.Entry<String, String> header) {
        if (header == null || header.getKey() == null || header.getValue() == null) {
            throw new IllegalArgumentException("headers must not contain null names or values");
        }
        if (containsLineBreak(header.getKey())) {
            throw new IllegalArgumentException("header names must not contain CR or LF");
        }
        String name = header.getKey().trim();
        if (!isValidFieldName(name)) {
            throw new IllegalArgumentException("header name must be an RFC HTTP field-name");
        }
        if (containsLineBreak(header.getValue())) {
            throw new IllegalArgumentException("header values must not contain CR or LF");
        }
        requireByteLength(name, maximumHeaderNameBytes, "header name");
        requireByteLength(header.getValue(), maximumHeaderValueBytes, "header value");
        addDistinctHeader(headers, name, header.getValue());
    }

    private static boolean isValidFieldName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int index = 0; index < name.length(); index++) {
            if (!isTokenCharacter(name.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTokenCharacter(char value) {
        return value >= '0' && value <= '9'
                || value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || "!#$%&'*+-.^_`|~".indexOf(value) >= 0;
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static void addDistinctHeader(Map<String, String> headers, String name, String value) {
        for (String existingName : headers.keySet()) {
            if (existingName.equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("headers must not contain case-insensitive duplicates");
            }
        }
        headers.put(name, value);
    }

    private static String boundedPayload(String payload, int maximumBytes, String fieldName) {
        String normalized = payload == null ? "" : payload;
        requireByteLength(normalized, maximumBytes, fieldName);
        return normalized;
    }

    private static void requireByteLength(String value, int maximumBytes, String fieldName) {
        if (value.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException(fieldName + " exceeds the configured maximum size");
        }
    }

    private static void validateLimit(int value, int hardMaximum, String fieldName) {
        if (value < 1) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        if (value > hardMaximum) {
            throw new IllegalArgumentException(fieldName + " exceeds the Core hard resource ceiling");
        }
    }
}
