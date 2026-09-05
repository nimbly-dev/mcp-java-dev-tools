package com.nimbly.mcpjavadevtools.server.core.operation.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Bounded validator for the JSON-schema subset used by the operation directory. */
public class OperationSchemaValidator {

    private OperationSchemaValidator() {
    }

    /** Returns deterministic validation violations without exposing input values. */
    public static List<String> violations(OperationSchema schema, JsonNode input) {
        if (schema == null) {
            throw new IllegalArgumentException("schema must not be null");
        }
        return violations(schema, input, () -> false);
    }

    public static List<String> violations(
            OperationSchema schema, JsonNode input, OperationValidationBudget budget) {
        if (schema == null || budget == null) {
            throw new IllegalArgumentException("schema and validation budget must not be null");
        }
        List<String> structuralViolations = OperationJsonTreeLimits.violations(input, budget);
        if (!structuralViolations.isEmpty()) {
            return structuralViolations;
        }
        JsonNode definition = schema.definition();
        return validate(definition, definition, input, budget);
    }

    public static List<String> violations(JsonNode schema, JsonNode root, JsonNode input) {
        List<String> structuralViolations = OperationJsonTreeLimits.violations(input);
        if (!structuralViolations.isEmpty()) {
            return structuralViolations;
        }
        try {
            OperationSchemaRules.validate(root);
            if (schema == null || !schema.equals(root)) {
                OperationSchemaRules.visit(schema, root.path("$defs"), new HashSet<>(), 1);
            }
        } catch (IllegalArgumentException exception) {
            return List.of("$ schema is outside the supported subset");
        }
        return validate(schema, root, input, () -> false);
    }

    static List<String> validate(
            JsonNode schema, JsonNode root, JsonNode input, OperationValidationBudget budget) {
        List<String> violations = new ArrayList<>();
        OperationSchemaValidationContext context = new OperationSchemaValidationContext(
                root, "$", violations, 0, budget);
        collect(schema, input == null ? NullNode.getInstance() : input, context);
        return List.copyOf(violations);
    }

    /** Fails closed when the input does not satisfy the supported schema subset. */
    public static void requireValid(OperationSchema schema, JsonNode input) {
        List<String> violations = violations(schema, input);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("operation input schema invalid: " + violations.getFirst());
        }
    }

    static void collect(JsonNode schema, JsonNode input,
            OperationSchemaValidationContext context) {
        if (context.expired()) {
            context.violations().add("$ JSON validation budget expired");
            return;
        }
        if (context.depth() > 16) {
            return;
        }
        if (schema == null) {
            context.violations().add(context.path() + " references missing schema");
            return;
        }
        if (schema.has("$ref")) {
            collect(context.referenceTarget(schema), input, context.child(context.path()));
            return;
        }
        JsonNode enumValues = schema.get("enum");
        if (OperationSchemaValueBounds.enumViolation(enumValues, input, context.path(), context)) {
            return;
        }
        JsonNode type = schema.get("type");
        if (type != null && !matches(type, input)) {
            context.violations().add(context.path() + " must match the declared type");
            return;
        }
        if (schema.has("oneOf") && !matchesOneOf(schema, input, context)) {
            if (context.expired()) {
                context.violations().add("$ JSON validation budget expired");
                return;
            }
            context.violations().add(context.path() + " does not match exactly one allowed schema");
            return;
        }
        OperationSchemaValueBounds.apply(
                schema, input, context.path(), context.violations(), context.budget());
        children(schema, input, context);
    }

    static void children(
            JsonNode schema,
            JsonNode input,
            OperationSchemaValidationContext context) {
        if (input.isObject()) {
            objectViolations(schema, input, context);
        }
        if (input.isArray()) {
            JsonNode items = schema.get("items");
            if (items != null) {
                for (int index = 0; index < input.size(); index++) {
                    collect(items, input.get(index), context.child(
                            context.path() + "[" + index + "]"));
                }
            }
        }
    }

    static boolean matchesOneOf(
            JsonNode schema, JsonNode input, OperationSchemaValidationContext context) {
        int matches = 0;
        for (JsonNode alternative : schema.get("oneOf")) {
            List<String> candidate = new ArrayList<>();
            OperationSchemaValidationContext candidateContext = new OperationSchemaValidationContext(
                    context.root(), context.path(), candidate, context.depth() + 1, context.budget());
            collect(alternative, input, candidateContext);
            if (candidate.isEmpty()) {
                matches++;
            }
        }
        return matches == 1;
    }

    static void objectViolations(
            JsonNode schema,
            JsonNode input,
            OperationSchemaValidationContext context) {
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode name : required) {
                if (context.expired()) {
                    context.violations().add("$ JSON validation budget expired");
                    return;
                }
                if (name.isTextual() && !input.has(name.asText())) {
                    context.violations().add(
                            context.path() + "." + name.asText() + " is required");
                }
            }
        }
        JsonNode properties = schema.get("properties");
        Iterator<Map.Entry<String, JsonNode>> fields = input.fields();
        while (fields.hasNext()) {
            if (context.expired()) {
                context.violations().add("$ JSON validation budget expired");
                return;
            }
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode property = properties == null ? null : properties.get(field.getKey());
            if (property == null && schema.path("additionalProperties").isBoolean()
                    && !schema.path("additionalProperties").asBoolean()) {
                context.violations().add(
                        context.path() + "." + field.getKey() + " is not supported");
            } else if (property != null) {
                collect(property, field.getValue(), context.child(
                        context.path() + "." + field.getKey()));
            } else if (schema.path("additionalProperties").isObject()) {
                collect(schema.path("additionalProperties"), field.getValue(), context.child(
                        context.path() + "." + field.getKey()));
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
            case "integer" -> OperationSchemaValueSemantics.isInteger(value);
            case "number" -> OperationSchemaValueSemantics.isJsonNumber(value);
            case "null" -> value.isNull();
            default -> true;
        };
    }

}
