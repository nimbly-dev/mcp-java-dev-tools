package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.protocol.http.ValidatedHttpRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Validates and bounds protocol-specific HTTP request input before network access. */
public final class HttpRequestValidator {

    private final HttpTransportSafetyPolicy safetyPolicy;
    private final HttpSensitiveDataRedactor redactor;
    private final ObjectMapper objectMapper;

    /** Creates the validator from its Core-owned policy and serialization collaborators. */
    public HttpRequestValidator(
            HttpTransportSafetyPolicy safetyPolicy,
            HttpSensitiveDataRedactor redactor,
            ObjectMapper objectMapper) {
        this.safetyPolicy = Objects.requireNonNull(safetyPolicy, "safetyPolicy must not be null");
        this.redactor = Objects.requireNonNull(redactor, "redactor must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /** Validates one HTTP request and returns its bounded execution representation. */
    public ValidatedHttpRequest validate(ExecuteTransportRequest request) {
        Object rawMethod = request.request().get("method");
        if (!(rawMethod instanceof String method) || method.isBlank()) {
            throw HttpRequestValidationException.payload(
                    "http_payload_invalid", "HTTP request method is required.");
        }
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        if (!safetyPolicy.isAllowedMethod(normalizedMethod)) {
            throw HttpRequestValidationException.payload(
                    "http_method_not_allowed", "HTTP request method is not allowed.");
        }
        URI uri = readTarget(request.request().get("url"));
        Map<String, String> headers = readHeaders(request.request().get("headers"));
        Object rawBody = request.request().get("body");
        byte[] body = readBody(rawBody);
        if (body.length > 0 && !(rawBody instanceof String)
                && headers.keySet().stream().noneMatch(name -> name.equalsIgnoreCase("content-type"))) {
            headers = new LinkedHashMap<>(headers);
            headers.put("Content-Type", "application/json");
        }
        int timeout = readTimeout(request.request().get("timeoutMs"));
        return new ValidatedHttpRequest(uri, normalizedMethod, headers, body, timeout);
    }

    private URI readTarget(Object rawUri) {
        if (!(rawUri instanceof String value) || value.isBlank()) {
            throw HttpRequestValidationException.payload(
                    "http_payload_invalid", "HTTP request URL is required.");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw HttpRequestValidationException.payload(
                    "http_url_invalid", "HTTP request URL is invalid.");
        }
        String targetReason = safetyPolicy.invalidTargetReason(uri);
        if (targetReason != null) {
            throw HttpRequestValidationException.target(targetReason, uri, redactor);
        }
        return uri;
    }

    private Map<String, String> readHeaders(Object rawHeaders) {
        if (rawHeaders == null) {
            return Map.of();
        }
        if (!(rawHeaders instanceof Map<?, ?> values)) {
            throw HttpRequestValidationException.payload(
                    "http_payload_invalid", "HTTP request headers are invalid.");
        }
        if (values.size() > HttpTransportSafetyPolicy.MAXIMUM_HEADER_COUNT) {
            throw HttpRequestValidationException.payload(
                    "http_headers_too_large", "HTTP request headers exceed the safety limit.");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        int totalBytes = 0;
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String rawName) || !(entry.getValue() instanceof String value)) {
                throw HttpRequestValidationException.payload(
                        "http_payload_invalid", "HTTP request headers are invalid.");
            }
            String name = rawName.trim();
            int maximumValueBytes = name.equalsIgnoreCase("authorization")
                    ? HttpTransportSafetyPolicy.MAXIMUM_REQUEST_HEADER_BYTES
                    : HttpTransportSafetyPolicy.MAXIMUM_HEADER_BYTES;
            boolean duplicate = headers.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(name));
            boolean invalidToken = name.isEmpty()
                    || name.chars().anyMatch(character -> !(Character.isLetterOrDigit(character)
                    || "!#$%&'*+-.^_`|~".indexOf(character) >= 0));
            totalBytes += name.getBytes(StandardCharsets.UTF_8).length
                    + value.getBytes(StandardCharsets.UTF_8).length + 4;
            if (duplicate || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw HttpRequestValidationException.payload(
                        "http_payload_invalid", "HTTP request headers are invalid.");
            }
            if (invalidToken
                    || name.getBytes(StandardCharsets.UTF_8).length > HttpTransportSafetyPolicy.MAXIMUM_HEADER_BYTES
                    || value.getBytes(StandardCharsets.UTF_8).length > maximumValueBytes
                    || totalBytes > HttpTransportSafetyPolicy.MAXIMUM_REQUEST_HEADER_BYTES) {
                throw HttpRequestValidationException.payload(
                        "http_headers_too_large", "HTTP request headers exceed the safety limit.");
            }
            headers.put(name, value);
        }
        return Map.copyOf(headers);
    }

    private byte[] readBody(Object rawBody) {
        if (rawBody == null) {
            return new byte[0];
        }
        if (!(rawBody instanceof String || rawBody instanceof Map<?, ?> || rawBody instanceof java.util.List<?>)) {
            throw HttpRequestValidationException.payload(
                    "http_payload_invalid", "HTTP request body is invalid.");
        }
        if (rawBody instanceof String text && text.trim().isEmpty()) {
            throw HttpRequestValidationException.payload(
                    "http_payload_invalid", "HTTP request body is invalid.");
        }
        try {
            byte[] bytes = rawBody instanceof String text
                    ? text.getBytes(StandardCharsets.UTF_8)
                    : objectMapper.writeValueAsBytes(rawBody);
            if (bytes.length > HttpTransportSafetyPolicy.MAXIMUM_REQUEST_BODY_BYTES) {
                throw HttpRequestValidationException.payload(
                        "http_request_body_too_large", "HTTP request body exceeds the safety limit.");
            }
            return bytes;
        } catch (JsonProcessingException exception) {
            throw HttpRequestValidationException.payload(
                    "http_payload_invalid", "HTTP request body is invalid.");
        }
    }

    private int readTimeout(Object rawTimeout) {
        if (rawTimeout == null) {
            return HttpTransportSafetyPolicy.DEFAULT_TIMEOUT_MILLIS;
        }
        if (!(rawTimeout instanceof Number number) || number.doubleValue() != number.longValue()) {
            throw HttpRequestValidationException.payload(
                    "http_payload_invalid", "HTTP timeoutMs must be an integer.");
        }
        long value = number.longValue();
        if (value < 1 || value > HttpTransportSafetyPolicy.MAXIMUM_TIMEOUT_MILLIS) {
            throw HttpRequestValidationException.payload(
                    "http_timeout_invalid", "HTTP timeoutMs is outside the allowed range.");
        }
        return (int) value;
    }
}
