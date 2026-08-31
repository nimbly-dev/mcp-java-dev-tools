package com.nimbly.mcpjavadevtools.server.core.operation.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded validator for the JSON-schema subset used by the operation directory. */
public final class OperationSchemaValidator {

    private OperationSchemaValidator() {
    }

    /** Returns deterministic validation violations without exposing input values. */
    public static List<String> violations(OperationSchema schema, JsonNode input) {
        if (schema == null) {
            throw new IllegalArgumentException("schema must not be null");
        }
        JsonNode definition = schema.definition();
        return violations(definition, definition, input);
    }

    public static List<String> violations(JsonNode schema, JsonNode root, JsonNode input) {
        List<String> violations = new ArrayList<>();
        collect(schema, root, input == null ? NullNode.getInstance() : input,
                "$", violations, 0);
        return List.copyOf(violations);
    }

    /** Fails closed when the input does not satisfy the supported schema subset. */
    public static void requireValid(OperationSchema schema, JsonNode input) {
        List<String> violations = violations(schema, input);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("operation input schema invalid: " + violations.getFirst());
        }
    }

    static void collect(
            JsonNode schema,
            JsonNode root,
            JsonNode input,
            String path,
            List<String> violations,
            int depth) {
        if (depth > 16 || schema == null || schema.isEmpty()) {
            return;
        }
        if (schema.has("$ref")) {
            collect(referenceTarget(schema, root), root, input, path, violations, depth + 1);
            return;
        }
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && enumValues.isArray() && !contains(enumValues, input)) {
            violations.add(path + " is not an allowed value");
            return;
        }
        JsonNode type = schema.get("type");
        if (type != null && !matches(type, input)) {
            violations.add(path + " must match the declared type");
            return;
        }
        if (schema.has("oneOf") && !matchesOneOf(schema, root, input, path, depth)) {
            violations.add(path + " does not match exactly one allowed schema");
            return;
        }
        valueBounds(schema, input, path, violations);
        String typeName = OperationSchemaRules.structuralType(type, input);
        if ("object".equals(typeName) && input.isObject()) {
            objectViolations(schema, root, input, path, violations, depth);
        }
        if ("array".equals(typeName) && input.isArray()) {
            JsonNode items = schema.get("items");
            if (items != null) {
                for (int index = 0; index < input.size(); index++) {
                    collect(items, root, input.get(index), path + "[" + index + "]", violations, depth + 1);
                }
            }
        }
    }

    static JsonNode referenceTarget(JsonNode schema, JsonNode root) {
        String reference = schema.get("$ref").asText();
        return root.path("$defs").get(reference.substring("#/$defs/".length()));
    }

    static boolean matchesOneOf(
            JsonNode schema, JsonNode root, JsonNode input, String path, int depth) {
        int matches = 0;
        for (JsonNode alternative : schema.get("oneOf")) {
            List<String> candidate = new ArrayList<>();
            collect(alternative, root, input, path, candidate, depth + 1);
            if (candidate.isEmpty()) {
                matches++;
            }
        }
        return matches == 1;
    }

    static void objectViolations(
            JsonNode schema,
            JsonNode root,
            JsonNode input,
            String path,
            List<String> violations,
            int depth) {
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode name : required) {
                if (name.isTextual() && !input.has(name.asText())) {
                    violations.add(path + "." + name.asText() + " is required");
                }
            }
        }
        JsonNode properties = schema.get("properties");
        Iterator<Map.Entry<String, JsonNode>> fields = input.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode property = properties == null ? null : properties.get(field.getKey());
            if (property == null && schema.path("additionalProperties").isBoolean()
                    && !schema.path("additionalProperties").asBoolean()) {
                violations.add(path + "." + field.getKey() + " is not supported");
            } else if (property != null) {
                collect(property, root, field.getValue(), path + "." + field.getKey(), violations, depth + 1);
            } else if (schema.path("additionalProperties").isObject()) {
                collect(schema.path("additionalProperties"), root, field.getValue(),
                        path + "." + field.getKey(), violations, depth + 1);
            }
        }
    }

    static boolean matches(JsonNode type, JsonNode value) {
        if (type == null) {
            return true;
        }
        if (type.isTextual()) {
            return matches(type.asText(), value);
        }
        for (JsonNode option : type) {
            if (option.isTextual() && matches(option.asText(), value)) {
                return true;
            }
        }
        return false;
    }

    static boolean matches(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "boolean" -> value.isBoolean();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    static boolean contains(JsonNode values, JsonNode candidate) {
        for (JsonNode value : values) {
            if (value.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    static void valueBounds(JsonNode schema, JsonNode value, String path, List<String> violations) {
        if (value.isTextual()) {
            if (schema.has("minLength") && value.textValue().length() < schema.get("minLength").asInt()) {
                violations.add(path + " is shorter than allowed");
            }
            if (schema.has("maxLength") && value.textValue().length() > schema.get("maxLength").asInt()) {
                violations.add(path + " is longer than allowed");
            }
            if (schema.has("pattern") && !Pattern.compile(schema.get("pattern").asText())
                    .matcher(value.textValue()).find()) {
                violations.add(path + " does not match the required pattern");
            }
        }
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) {
                violations.add(path + " has fewer items than allowed");
            }
            if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) {
                violations.add(path + " has more items than allowed");
            }
            if (schema.path("uniqueItems").asBoolean(false)) {
                for (int index = 0; index < value.size(); index++) {
                    for (int prior = 0; prior < index; prior++) {
                        if (value.get(prior).equals(value.get(index))) {
                            violations.add(path + " must contain unique items");
                            return;
                        }
                    }
                }
            }
        }
        if (value.isNumber()) {
            if (schema.has("minimum") && value.asDouble() < schema.get("minimum").asDouble()) {
                violations.add(path + " is below the minimum");
            }
            if (schema.has("maximum") && value.asDouble() > schema.get("maximum").asDouble()) {
                violations.add(path + " is above the maximum");
            }
        }
    }
}
