package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result.RegressionSuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.preflight.RegressionPlanPreflight;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Executes ordered HTTP triggers through the public Transport Execution Feature. */
public final class RegressionPlanExecutor {

    private final RegressionPlanPreflight preflight;
    private final TransportExecutionFeature transport;
    private final ObjectMapper mapper;

    /** Creates the executor from its Core Feature collaborator. */
    public RegressionPlanExecutor(RegressionPlanPreflight preflight, TransportExecutionFeature transport, ObjectMapper mapper) {
        this.preflight = Objects.requireNonNull(preflight, "preflight must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /** Executes preflight-valid steps without emitting supplied context or response bodies. */
    public RegressionSuiteResult execute(JsonNode input) {
        RegressionSuiteResult checked = preflight.validate(input);
        if (!"ready".equals(checked.status())) {
            return checked;
        }
        Map<String, Object> context = context(input.path("providedContext"));
        Map<Integer, Map<String, Object>> outputs = new LinkedHashMap<>();
        List<Map<String, Object>> outcomes = new ArrayList<>();
        List<JsonNode> steps = steps(input.path("contract").path("steps"));
        for (JsonNode step : steps) {
            Map<String, Object> outcome = executeStep(step, context, outputs);
            outcomes.add(outcome);
            if (!"pass".equals(outcome.get("status")) && !"skipped".equals(outcome.get("status"))) {
                return failed(outcomes, String.valueOf(outcome.get("reasonCode")));
            }
        }
        return RegressionSuiteResult.ready(Map.of("runStatus", "pass", "steps", List.copyOf(outcomes)));
    }

    private Map<String, Object> context(JsonNode node) {
        return node.isObject() ? new LinkedHashMap<>(mapper.convertValue(node, new TypeReference<>() {
        }))
                : new LinkedHashMap<>();
    }

    private List<JsonNode> steps(JsonNode node) {
        List<JsonNode> ordered = new ArrayList<>();
        node.forEach(ordered::add);
        ordered.sort(Comparator.comparingInt(step -> step.path("order").asInt()));
        return ordered;
    }

    private Map<String, Object> executeStep(
            JsonNode step,
            Map<String, Object> context,
            Map<Integer, Map<String, Object>> outputs) {
        RegressionStepConditionEvaluator.Evaluation condition = new RegressionStepConditionEvaluator()
                .evaluate(step.path("when"), context, outputs, step.path("order").asInt());
        if (condition.blocked()) {
            return outcome(step, "blocked", condition.reasonCode(), null);
        }
        if (!condition.matches()) {
            return outcome(step, "skipped", "step_condition_false", null);
        }
        Map<String, Object> request = httpRequest(step.path("transport").path("http"), context);
        if (!request.containsKey("url")) {
            return outcome(step, "blocked", "http_url_missing", null);
        }
        ExecuteTransportResult result = transport.execute(
                new ExecuteTransportRequest(TransportProtocol.HTTP, request, true));
        return evaluate(step, result, context, outputs);
    }

    private Map<String, Object> httpRequest(JsonNode node, Map<String, Object> context) {
        Map<String, Object> request = resolveMap(mapper.convertValue(node, new TypeReference<>() {
        }), context);
        request.putIfAbsent("method", "GET");
        String url = text(request.get("url"));
        String path = firstText(request.get("pathTemplate"), request.get("path"));
        if (url == null && path != null && !absolute(path)) {
            String base = firstText(context.get("apiBaseUrl"), context.get("baseUrl"));
            if (base != null) {
                request.put("url", base.replaceFirst("/$", "") + (path.startsWith("/") ? "" : "/") + path);
            }
        }
        return request;
    }

    private Map<String, Object> resolveMap(Map<String, Object> values, Map<String, Object> context) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        values.forEach((key, value) -> resolved.put(key, resolve(value, context)));
        return resolved;
    }

    private Object resolve(Object value, Map<String, Object> context) {
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

    private String replace(String text, Map<String, Object> context) {
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

    private Map<String, Object> evaluate(
            JsonNode step,
            ExecuteTransportResult result,
            Map<String, Object> context,
            Map<Integer, Map<String, Object>> outputs) {
        if (!"ok".equals(result.status())) {
            return outcome(step, "blocked", result.reasonCode(), result);
        }
        Map<String, Object> response = response(result);
        String failure = new RegressionStepExpectationEvaluator(mapper).firstFailure(step.path("expect"), response);
        if (failure != null) {
            return outcome(step, "fail", failure, result);
        }
        Map<String, Object> output = Map.of("response", response, "status", "pass");
        outputs.put(step.path("order").asInt(), output);
        String extractionFailure = extract(step.path("extract"), output, context);
        return extractionFailure == null ? outcome(step, "pass", "ok", result)
                : outcome(step, "blocked", extractionFailure, result);
    }

    private Map<String, Object> response(ExecuteTransportResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", result.statusCode());
        response.put("statusCode", result.statusCode());
        response.put("headers", result.headers());
        response.put("body", result.bodyPreview());
        response.put("bodyJson", json(result.bodyPreview()));
        return response;
    }

    private Object json(String body) {
        try {
            return body == null || body.isBlank() ? null : mapper.readValue(body, Object.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extract(JsonNode extracts, Map<String, Object> output, Map<String, Object> context) {
        for (JsonNode extract : extracts) {
            Object value = RegressionStepExpectationEvaluator.read(output, extract.path("from").asText());
            if (value == null && extract.path("required").asBoolean()) {
                return "extract_path_missing";
            }
            if (value != null && !extract.path("as").asText().isBlank()) {
                context.put(extract.path("as").asText(), value);
            }
        }
        return null;
    }

    private Map<String, Object> outcome(JsonNode step, String status, String reasonCode, ExecuteTransportResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("order", step.path("order").asInt());
        output.put("id", step.path("id").asText());
        output.put("status", status);
        output.put("reasonCode", reasonCode == null ? "transport_failed" : reasonCode);
        if (result != null) {
            output.put("statusCode", result.statusCode());
            output.put("durationMs", result.durationMs());
        }
        return Map.copyOf(output);
    }

    private RegressionSuiteResult failed(List<Map<String, Object>> outcomes, String reasonCode) {
        return new RegressionSuiteResult("failed", reasonCode, "inspect the failed Regression step", Map.of(),
                Map.of("runStatus", "fail", "steps", List.copyOf(outcomes)));
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
}
