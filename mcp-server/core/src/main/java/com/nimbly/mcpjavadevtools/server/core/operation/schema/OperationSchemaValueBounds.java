package com.nimbly.mcpjavadevtools.server.core.operation.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyLimits;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Applies JSON Schema string, collection, and numeric bounds with exact value semantics. */
public class OperationSchemaValueBounds {

    private OperationSchemaValueBounds() {
    }

    static boolean enumViolation(
            JsonNode values,
            JsonNode candidate,
            String path,
            OperationSchemaValidationContext context) {
        if (values == null || !values.isArray()) {
            return false;
        }
        for (JsonNode value : values) {
            if (context.expired()) {
                context.violations().add("$ JSON validation budget expired");
                return true;
            }
            if (OperationSchemaValueSemantics.equalsValue(value, candidate, context.budget())) {
                return false;
            }
            if (context.expired()) {
                context.violations().add("$ JSON validation budget expired");
                return true;
            }
        }
        context.violations().add(path + " is not an allowed value");
        return true;
    }

    static void apply(
            JsonNode schema,
            JsonNode value,
            String path,
            List<String> violations,
            OperationValidationBudget budget) {
        if (value.isTextual()) {
            stringBounds(schema, value, path, violations);
        }
        if (value.isArray()) {
            arrayBounds(schema, value, path, violations, budget);
        }
        if (OperationSchemaValueSemantics.isJsonNumber(value)) {
            numberBounds(schema, value, path, violations);
        }
    }

    static void stringBounds(
            JsonNode schema, JsonNode value, String path, List<String> violations) {
        if (schema.has("minLength")
                && OperationSchemaValueSemantics.codePointLength(value.textValue())
                < schema.get("minLength").asInt()) {
            violations.add(path + " is shorter than allowed");
        }
        if (schema.has("maxLength")
                && OperationSchemaValueSemantics.codePointLength(value.textValue())
                > schema.get("maxLength").asInt()) {
            violations.add(path + " is longer than allowed");
        }
        if (schema.has("pattern")) {
            if (OperationJsonTreeLimits.utf8Length(value.textValue())
                    > OperationSafetyLimits.MAX_PATTERN_INPUT_BYTES) {
                violations.add(path + " exceeds the bounded pattern input size");
            } else if (!Pattern.compile(schema.get("pattern").asText())
                    .matcher(value.textValue()).find()) {
                violations.add(path + " does not match the required pattern");
            }
        }
    }

    static void arrayBounds(
            JsonNode schema,
            JsonNode value,
            String path,
            List<String> violations,
            OperationValidationBudget budget) {
        if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) {
            violations.add(path + " has fewer items than allowed");
        }
        if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) {
            violations.add(path + " has more items than allowed");
        }
        if (schema.path("uniqueItems").asBoolean(false)) {
            Map<Integer, List<JsonNode>> seen = new HashMap<>();
            for (JsonNode item : value) {
                if (budget.expired()) {
                    violations.add("$ JSON validation budget expired");
                    return;
                }
                int hash = OperationSchemaValueSemantics.hashValue(item, budget);
                if (budget.expired()) {
                    violations.add("$ JSON validation budget expired");
                    return;
                }
                List<JsonNode> candidates = seen.get(hash);
                if (candidates != null) {
                    for (JsonNode candidate : candidates) {
                        if (budget.expired()) {
                            violations.add("$ JSON validation budget expired");
                            return;
                        }
                        if (OperationSchemaValueSemantics.equalsValue(candidate, item, budget)) {
                            if (budget.expired()) {
                                violations.add("$ JSON validation budget expired");
                                return;
                            }
                            violations.add(path + " must contain unique items");
                            return;
                        }
                    }
                }
                seen.computeIfAbsent(hash, ignored -> new ArrayList<>()).add(item);
            }
        }
    }

    static void numberBounds(
            JsonNode schema, JsonNode value, String path, List<String> violations) {
        if (schema.has("minimum")
                && OperationSchemaValueSemantics.compareNumbers(value, schema.get("minimum")) < 0) {
            violations.add(path + " is below the minimum");
        }
        if (schema.has("maximum")
                && OperationSchemaValueSemantics.compareNumbers(value, schema.get("maximum")) > 0) {
            violations.add(path + " is above the maximum");
        }
    }
}
