package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result.RegressionSuiteResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tracks each actual extraction write and its classification, including overwritten secrets. */
final class RegressionExecutionContext {
    private final Map<String, Object> values;
    private final Map<String, JsonNode> classifications = new LinkedHashMap<>();
    private final List<JsonNode> secrets = new ArrayList<>();
    private final ObjectMapper mapper;
    private boolean secretExtractionObserved;
    private boolean redactFreeformEvidence;

    RegressionExecutionContext(ObjectMapper mapper, Map<String, Object> values) {
        this.mapper = mapper;
        this.values = new LinkedHashMap<>(values);
    }

    Map<String, Object> values() {
        return values;
    }

    void extract(JsonNode declaration, Object value) {
        String name = declaration.path("as").asText();
        values.put(name, value);
        classifications.put(name, declaration.deepCopy());
        if (declaration.path("secret").asBoolean()) {
            secretExtractionObserved = true;
            rememberSecret(mapper.valueToTree(value));
        }
    }

    void classifyPrerequisites(JsonNode input) {
        for (JsonNode prerequisite : input.path("contract").path("prerequisites")) {
            if (prerequisite.path("secret").asBoolean()) {
                rememberSecret(mapper.valueToTree(values.get(prerequisite.path("key").asText())));
            }
        }
    }

    private void rememberSecret(JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isContainerNode()) {
            value.forEach(this::rememberSecret);
        } else if (!value.isTextual() || !value.asText().isEmpty()) {
            secrets.add(value);
        }
    }

    Map<String, Object> safeExtractions() {
        Map<String, Object> safe = new LinkedHashMap<>();
        classifications.forEach((name, declaration) -> {
            if (!declaration.path("secret").asBoolean() && values.containsKey(name)) {
                safe.put(name, values.get(name));
            }
        });
        return Map.copyOf(safe);
    }

    Map<String, JsonNode> safeClassifications() {
        Map<String, JsonNode> safe = new LinkedHashMap<>();
        classifications.forEach((name, declaration) -> {
            if (!declaration.path("secret").asBoolean()) {
                safe.put(name, declaration);
            }
        });
        return Map.copyOf(safe);
    }

    void restore(JsonNode extracted, JsonNode declarations) {
        declarations.fields().forEachRemaining(entry -> {
            JsonNode value = extracted.get(entry.getKey());
            if (value != null && !entry.getValue().path("secret").asBoolean()) {
                extract(entry.getValue(), mapper.convertValue(value, Object.class));
            }
        });
    }

    void restoreEvidencePolicy(boolean hadSecretExtraction) {
        redactFreeformEvidence = hadSecretExtraction;
    }

    boolean requiresResumeRedaction() {
        return secretExtractionObserved || redactFreeformEvidence;
    }

    Map<String, Object> promoted() {
        Map<String, Object> promoted = new LinkedHashMap<>();
        classifications.forEach((name, declaration) -> {
            if ("suite".equals(declaration.path("scope").asText())
                    && declaration.path("secret").isBoolean() && !declaration.path("secret").asBoolean()) {
                promoted.put(name.startsWith("suite.") ? name : "suite." + name, values.get(name));
            }
        });
        return Map.copyOf(promoted);
    }

    RegressionSuiteResult sanitize(RegressionSuiteResult result) {
        Map<String, Object> details = mapper.convertValue(sanitizeDetails(mapper.valueToTree(result.details())),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        return new RegressionSuiteResult(result.status(), result.reasonCode(), result.nextAction(), result.reasonMeta(), details);
    }

    private JsonNode sanitizeDetails(JsonNode details) {
        var output = (com.fasterxml.jackson.databind.node.ObjectNode) details.deepCopy();
        for (String section : List.of("watchers", "externalVerification")) {
            for (JsonNode row : output.path(section)) {
                sanitizeObservation(row.path("response"), List.of("body", "bodyJson", "headers"));
                sanitizeObservation(row.path("sql"), List.of("rows", "firstRow"));
            }
        }
        sanitizeFields(output, List.of("suiteContext"));
        if (output.path("correlation").isObject()) {
            var correlation = (com.fasterxml.jackson.databind.node.ObjectNode) output.path("correlation");
            sanitizeFields(correlation, List.of("keyValue"));
            for (JsonNode event : correlation.path("timeline")) {
                sanitizeFields((com.fasterxml.jackson.databind.node.ObjectNode) event, List.of("keyValue"));
            }
        }
        if (output.path("continuation").isObject()) {
            var checkpoint = (com.fasterxml.jackson.databind.node.ObjectNode) output.path("continuation");
            sanitizeFields(checkpoint, List.of("context"));
            checkpoint.set("details", sanitizeDetails(checkpoint.path("details")));
        }
        return output;
    }

    private void sanitizeObservation(JsonNode observation, List<String> dataFields) {
        if (!observation.isObject()) {
            return;
        }
        var object = (com.fasterxml.jackson.databind.node.ObjectNode) observation;
        for (String field : dataFields) {
            if (object.has(field)) {
                // Raw bodies may encode secrets differently from their parsed values (including truncated JSON).
                boolean redact = redactFreeformEvidence || ("body".equals(field) && !secrets.isEmpty());
                object.set(field, redact ? TextNode.valueOf("[REDACTED]") : sanitize(object.get(field)));
            }
        }
    }

    private void sanitizeFields(com.fasterxml.jackson.databind.node.ObjectNode owner, List<String> fields) {
        for (String field : fields) {
            if (owner.has(field)) {
                owner.set(field, sanitize(owner.get(field)));
            }
        }
    }

    private JsonNode sanitize(JsonNode value) {
        if (value.isValueNode()) {
            return sanitizeScalar(value);
        }
        JsonNode copy = value.deepCopy();
        if (copy.isObject()) {
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) copy;
            object.properties().forEach(entry -> entry.setValue(sanitize(entry.getValue())));
        } else if (copy.isArray()) {
            var array = (com.fasterxml.jackson.databind.node.ArrayNode) copy;
            for (int index = 0; index < array.size(); index++) {
                array.set(index, sanitize(array.get(index)));
            }
        }
        return copy;
    }

    private JsonNode sanitizeScalar(JsonNode value) {
        if (secrets.contains(value)) {
            return TextNode.valueOf("[REDACTED]");
        }
        if (!value.isTextual()) {
            return value;
        }
        String text = value.asText();
        for (JsonNode secret : secrets) {
            text = text.replace(secret.asText(), "[REDACTED]");
        }
        return TextNode.valueOf(text);
    }
}
