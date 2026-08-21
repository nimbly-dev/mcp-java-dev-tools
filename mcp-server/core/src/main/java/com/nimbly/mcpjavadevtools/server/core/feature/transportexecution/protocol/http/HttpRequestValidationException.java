package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic HTTP validation failure retained inside the Core provider boundary. */
final class HttpRequestValidationException extends RuntimeException {

    private final String reasonCode;
    private final String failedStep;
    private final String redactedUrl;

    private HttpRequestValidationException(
            String reasonCode,
            String message,
            String failedStep,
            String redactedUrl) {
        super(message);
        this.reasonCode = reasonCode;
        this.failedStep = failedStep;
        this.redactedUrl = redactedUrl;
    }

    static HttpRequestValidationException payload(String reasonCode, String message) {
        return new HttpRequestValidationException(
                reasonCode, message, "transport_execute_http_payload", null);
    }

    static HttpRequestValidationException target(
            String reasonCode,
            URI uri,
            HttpSensitiveDataRedactor redactor) {
        String message = switch (reasonCode) {
            case "http_scheme_not_allowed" -> "HTTP URL scheme is not allowed.";
            case "http_host_required" -> "HTTP URL host is required.";
            case "http_user_info_not_allowed" -> "HTTP URL user-info is not allowed.";
            case "http_host_not_allowed" -> "HTTP URL host is not allowed by the safety policy.";
            default -> "HTTP URL is invalid.";
        };
        String safeUrl = uri != null && uri.getUserInfo() == null ? redactor.redactUri(uri) : null;
        return new HttpRequestValidationException(
                reasonCode, message, "transport_execute_http_target", safeUrl);
    }

    String reasonCode() {
        return reasonCode;
    }

    Map<String, Object> reasonMeta() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("failedStep", failedStep);
        if (redactedUrl != null) {
            metadata.put("url", redactedUrl);
        }
        return Map.copyOf(metadata);
    }
}
