package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Shared HTTP and expectation mechanics for post-trigger Regression verification. */
final class RegressionVerificationSupport {

    private final TransportExecutionFeature transport;
    private final ObjectMapper mapper;

    RegressionVerificationSupport(TransportExecutionFeature transport, ObjectMapper mapper) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    Observation executeHttp(JsonNode requestNode, Map<String, Object> context) {
        Map<String, Object> request = resolveMap(
                mapper.convertValue(requestNode, new TypeReference<>() { }), context);
        request.putIfAbsent("method", "GET");
        String url = text(request.get("url"));
        String path = firstText(request.get("pathTemplate"), request.get("path"));
        if (url == null && path != null && !absolute(path)) {
            String base = firstText(context.get("apiBaseUrl"), context.get("baseUrl"));
            if (base != null) {
                request.put("url", base.replaceFirst("/$", "") + (path.startsWith("/") ? "" : "/") + path);
            }
        }
        if (!request.containsKey("url")) {
            return Observation.failure("verification_http_url_missing");
        }
        ExecuteTransportResult result = transport.execute(
                new ExecuteTransportRequest(TransportProtocol.HTTP, request, true));
        if (!"pass".equals(result.status())) {
            return Observation.failure(result.reasonCode() == null
                    ? "verification_transport_failed" : result.reasonCode());
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", result.statusCode());
        response.put("statusCode", result.statusCode());
        response.put("headers", result.headers());
        response.put("body", result.bodyPreview());
        response.put("bodyJson", json(result.bodyPreview()));
        return Observation.success(Collections.unmodifiableMap(response), result.durationMs());
    }

    String firstExpectationFailure(JsonNode expectations, Observation observation) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", observation.status());
        envelope.put("response", observation.response());
        return firstEnvelopeExpectationFailure(expectations, Map.copyOf(envelope));
    }

    String firstEnvelopeExpectationFailure(JsonNode expectations, Map<String, Object> envelope) {
        return firstEnvelopeExpectationFailure(expectations, envelope, Map.of());
    }

    String firstEnvelopeExpectationFailure(
            JsonNode expectations,
            Map<String, Object> envelope,
            Map<String, Object> context) {
        for (JsonNode expectation : expectations) {
            Object actual = RegressionStepExpectationEvaluator.read(
                    envelope, expectation.path("actualPath").asText());
            Object expected = expectation.has("expected")
                    ? resolve(mapper.convertValue(expectation.get("expected"), Object.class), context) : null;
            if (actual == null && !expectation.path("required").asBoolean(true)) {
                continue;
            }
            if (!matches(expectation.path("operator").asText(), actual, expected)) {
                return "verification_expectation_failed";
            }
        }
        return null;
    }

    private boolean matches(String operator, Object actual, Object expected) {
        if ("field_exists".equals(operator)) {
            return actual != null;
        }
        if ("field_equals".equals(operator) || "outcome_status".equals(operator)) {
            if (actual instanceof Number left && expected instanceof Number right) {
                return BigDecimal.valueOf(left.doubleValue())
                        .compareTo(BigDecimal.valueOf(right.doubleValue())) == 0;
            }
            return Objects.equals(actual, expected);
        }
        if ("field_matches_regex".equals(operator)) {
            if (!(actual instanceof String text) || !(expected instanceof String regex)) {
                return false;
            }
            try {
                return Pattern.compile(regex).matcher(text).find();
            } catch (PatternSyntaxException exception) {
                return false;
            }
        }
        if ("numeric_gte".equals(operator) || "numeric_lte".equals(operator)) {
            if (!(actual instanceof Number left) || !(expected instanceof Number right)) {
                return false;
            }
            int compared = BigDecimal.valueOf(left.doubleValue())
                    .compareTo(BigDecimal.valueOf(right.doubleValue()));
            return "numeric_gte".equals(operator) ? compared >= 0 : compared <= 0;
        }
        if ("contains".equals(operator)) {
            return actual instanceof String text && expected instanceof String item && text.contains(item)
                    || actual instanceof List<?> values && values.contains(expected);
        }
        return false;
    }

    Object read(Map<String, Object> response, String path) {
        return RegressionStepExpectationEvaluator.read(Map.of("response", response), path);
    }

    Object resolve(Object value, Map<String, Object> context) {
        if (value instanceof String text) {
            return replace(text, context);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, entry) -> nested.put(String.valueOf(key), resolve(entry, context)));
            return nested;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> resolve(item, context)).toList();
        }
        return value;
    }

    String replace(String text, Map<String, Object> context) {
        String resolved = text;
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            if (entry.getValue() != null
                    && !(entry.getValue() instanceof Map<?, ?>)
                    && !(entry.getValue() instanceof List<?>)) {
                String value = String.valueOf(entry.getValue());
                resolved = resolved.replace("${" + entry.getKey() + "}", value);
                resolved = resolved.replace("{{" + entry.getKey() + "}}", value);
            }
        }
        return resolved;
    }

    private Map<String, Object> resolveMap(Map<String, Object> values, Map<String, Object> context) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        values.forEach((key, value) -> resolved.put(key, resolve(value, context)));
        return resolved;
    }

    private Object json(String body) {
        try {
            return body == null || body.isBlank() ? null : mapper.readValue(body, Object.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    private static String firstText(Object first, Object second) {
        String value = text(first);
        return value == null ? text(second) : value;
    }

    private static boolean absolute(String value) {
        return value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8);
    }

    record Observation(String status, String reasonCode, Map<String, Object> response, long durationMs) {
        private static Observation success(Map<String, Object> response, long durationMs) {
            return new Observation("pass", "ok", response, durationMs);
        }

        private static Observation failure(String reasonCode) {
            return new Observation("blocked_runtime", reasonCode, Map.of(), 0L);
        }

        boolean success() {
            return "pass".equals(status);
        }
    }
}
