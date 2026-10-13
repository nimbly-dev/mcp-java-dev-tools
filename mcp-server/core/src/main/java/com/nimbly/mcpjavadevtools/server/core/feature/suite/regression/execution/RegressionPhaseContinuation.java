package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result.RegressionSuiteResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Regression-owned, secret-free phase checkpoint carried by canonical run persistence. */
final class RegressionPhaseContinuation {
    private final ObjectMapper mapper;
    private final JsonNode input;
    private final RegressionExecutionContext context;
    private final Map<String, Object> details = new LinkedHashMap<>();

    RegressionPhaseContinuation(ObjectMapper mapper, JsonNode input, RegressionExecutionContext context) {
        this.mapper = mapper;
        this.input = input;
        this.context = context;
    }

    boolean resumed() {
        return input.has("continuation");
    }

    boolean valid() {
        JsonNode prior = input.path("continuation");
        return !resumed() || (prior.isObject() && fingerprint().equals(prior.path("fingerprint").asText())
                && List.of("watchers", "external_verification").contains(prior.path("phase").asText())
                && prior.path("context").isObject() && prior.path("classifications").isObject() && prior.path("details").isObject()
                && prior.path("details").path("steps").isArray());
    }

    void restore() {
        JsonNode prior = input.path("continuation");
        context.restore(prior.path("context"), prior.path("classifications"));
        context.restoreEvidencePolicy(prior.path("redactEvidenceOnResume").asBoolean());
        details.putAll(mapper.convertValue(prior.path("details"), new TypeReference<Map<String, Object>>() { }));
    }

    List<Map<String, Object>> steps() {
        return mapper.convertValue(details.getOrDefault("steps", List.of()), new TypeReference<>() { });
    }

    Map<Integer, Map<String, Object>> dependencies() {
        Map<Integer, Map<String, Object>> outputs = new LinkedHashMap<>();
        for (Map<String, Object> step : steps()) {
            if ("pass".equals(step.get("status")) && step.get("order") instanceof Number order) {
                outputs.put(order.intValue(), Map.of("status", "pass"));
            }
        }
        return outputs;
    }

    Map<String, Object> details() {
        return details;
    }

    RegressionExecutionContext context() {
        return context;
    }

    JsonNode prior(String section) {
        return mapper.valueToTree(details.getOrDefault(section, List.of()));
    }

    RegressionSuiteResult waiting(String phase) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("phase", phase);
        checkpoint.put("fingerprint", fingerprint());
        checkpoint.put("context", context.safeExtractions());
        checkpoint.put("classifications", context.safeClassifications());
        checkpoint.put("redactEvidenceOnResume", context.requiresResumeRedaction());
        checkpoint.put("details", Map.copyOf(details));
        Map<String, Object> output = new LinkedHashMap<>(details);
        output.put("runStatus", "in_progress");
        output.put("continuation", Map.copyOf(checkpoint));
        return new RegressionSuiteResult("in_progress", "regression_phase_in_progress",
                "resume the same suiteRunId to continue the active phase", Map.of(), Map.copyOf(output));
    }

    private String fingerprint() {
        // Bind source identities and non-secret supplied context; resolve current credentials on every call.
        var supplied = mapper.createObjectNode();
        if (input.path("providedContext").isObject()) {
            supplied.setAll((com.fasterxml.jackson.databind.node.ObjectNode) input.path("providedContext"));
        }
        input.path("credentialBindings").fieldNames().forEachRemaining(supplied::remove);
        for (JsonNode prerequisite : input.path("contract").path("prerequisites")) {
            if (prerequisite.path("secret").asBoolean()) {
                supplied.remove(prerequisite.path("key").asText());
            }
        }
        String value = input.path("metadata").toString() + "\n" + input.path("contract")
                + "\n" + supplied + "\n" + input.path("credentialBindings") + "\n" + input.path("credentialSource");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
