package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic normalized outcome for one transport execution. */
public record ExecuteTransportResult(
        String status,
        String reasonCode,
        String nextActionCode,
        String errorMessage,
        Map<String, Object> reasonMeta,
        String protocol,
        Integer statusCode,
        Map<String, String> headers,
        String bodyPreview,
        long durationMs) {

    /** Normalize nullable maps and the minimum observable duration. */
    public ExecuteTransportResult {
        reasonMeta = copyMap(reasonMeta);
        headers = copyHeaders(headers);
        durationMs = Math.max(1, durationMs);
    }

    /** Creates a successful or HTTP-failure response. */
    public static ExecuteTransportResult httpResponse(
            String status,
            String protocol,
            int statusCode,
            Map<String, String> headers,
            String bodyPreview,
            long durationMs) {
        return new ExecuteTransportResult(
                status, null, null, null, Map.of(), protocol, statusCode, headers, bodyPreview, durationMs);
    }

    /** Creates a deterministic invalid or policy-blocked response. */
    public static ExecuteTransportResult blockedInvalid(
            String reasonCode,
            String message,
            String protocol,
            long durationMs) {
        return blockedInvalid(reasonCode, message, protocol, durationMs, Map.of("failedStep", "transport_execute"));
    }

    /** Creates an invalid response with bounded provider metadata. */
    public static ExecuteTransportResult blockedInvalid(
            String reasonCode,
            String message,
            String protocol,
            long durationMs,
            Map<String, Object> reasonMeta) {
        return blocked(reasonCode, message, "blocked_invalid", protocol, durationMs, reasonMeta);
    }

    /** Creates a deterministic runtime-blocked response. */
    public static ExecuteTransportResult blockedRuntime(
            String reasonCode,
            String message,
            String protocol,
            long durationMs) {
        return blockedRuntime(reasonCode, message, protocol, durationMs, Map.of("failedStep", "transport_execute"));
    }

    /** Creates a runtime-blocked response with bounded provider metadata. */
    public static ExecuteTransportResult blockedRuntime(
            String reasonCode,
            String message,
            String protocol,
            long durationMs,
            Map<String, Object> reasonMeta) {
        return blocked(reasonCode, message, "blocked_runtime", protocol, durationMs, reasonMeta);
    }

    /** Creates the stable unsupported-provider outcome. */
    public static ExecuteTransportResult unsupported(String protocol) {
        return blockedInvalid(
                "transport_not_supported",
                "Unsupported transport protocol '" + protocol + "'.",
                protocol,
                1,
                Map.of(
                        "failedStep", "transport_execute_protocol",
                        "protocol", protocol));
    }

    private static ExecuteTransportResult blocked(
            String reasonCode,
            String message,
            String status,
            String protocol,
            long durationMs,
            Map<String, Object> reasonMeta) {
        return new ExecuteTransportResult(
                status,
                reasonCode,
                reasonCode,
                message,
                reasonMeta,
                protocol,
                null,
                Map.of(),
                null,
                durationMs);
    }

    private static Map<String, Object> copyMap(Map<String, Object> values) {
        return values == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(values));
    }

    private static Map<String, String> copyHeaders(Map<String, String> values) {
        return values == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(values));
    }
}
