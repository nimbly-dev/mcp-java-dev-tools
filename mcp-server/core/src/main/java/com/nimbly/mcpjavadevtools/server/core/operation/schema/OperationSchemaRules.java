package com.nimbly.mcpjavadevtools.server.core.operation.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates the bounded Draft 2020-12 schema subset accepted by Core. */
public class OperationSchemaRules {

    public static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";
    private static final Set<String> KEYWORDS = Set.of(
            "$schema", "$defs", "$ref", "type", "properties", "required",
            "additionalProperties", "items", "minItems", "maxItems", "uniqueItems",
            "minimum", "maximum", "minLength", "maxLength", "pattern", "enum",
            "oneOf", "default");
    private static final Set<String> TYPES = Set.of(
            "object", "array", "string", "integer", "number", "boolean", "null");

    private OperationSchemaRules() {
    }

    static void validate(JsonNode schema) {
        visit(schema, schema.path("$defs"), new HashSet<>(), 0);
    }

    static void visit(JsonNode schema, JsonNode definitions, Set<String> activeRefs, int depth) {
        if (schema == null || !schema.isObject() || depth > 16) {
            throw new IllegalArgumentException("operation schema is outside the supported subset");
        }
        Iterator<String> names = schema.fieldNames();
        while (names.hasNext()) {
            if (!KEYWORDS.contains(names.next())) {
                throw new IllegalArgumentException("operation schema contains an unsupported keyword");
            }
        }
        JsonNode dialect = schema.get("$schema");
        if (depth == 0 && (dialect == null || !dialect.isTextual()
                || !DRAFT_2020_12.equals(dialect.asText()))) {
            throw new IllegalArgumentException("operation schema must use Draft 2020-12");
        }
        type(schema.get("type"));
        required(schema, definitions, activeRefs, depth);
        enumValues(schema.get("enum"));
        bounds(schema);
        if (schema.has("oneOf")) {
            JsonNode alternatives = schema.get("oneOf");
            if (!alternatives.isArray() || alternatives.isEmpty() || alternatives.size() > 8) {
                throw new IllegalArgumentException("operation schema oneOf is outside the supported bounds");
            }
            for (JsonNode alternative : alternatives) {
                visit(alternative, definitions, activeRefs, depth + 1);
            }
        }
        if (schema.has("$ref")) {
            localReference(schema, definitions, activeRefs, depth);
        }
        if (depth == 0 && !OperationJsonTreeLimits.violations(schema).isEmpty()) {
            throw new IllegalArgumentException("operation schema contains an invalid JSON value");
        }
    }

    static void required(JsonNode schema, JsonNode definitions, Set<String> activeRefs, int depth) {
        JsonNode properties = schema.get("properties");
        if (properties != null && !properties.isObject()) {
            throw new IllegalArgumentException("operation schema properties must be an object");
        }
        if (properties != null) {
            for (JsonNode property : properties) {
                visit(property, definitions, activeRefs, depth + 1);
            }
        }
        requiredFields(properties, schema.get("required"));
        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && !additional.isBoolean() && !additional.isObject()) {
            throw new IllegalArgumentException("operation schema additionalProperties is invalid");
        }
        if (additional != null && additional.isObject()) {
            visit(additional, definitions, activeRefs, depth + 1);
        }
        JsonNode items = schema.get("items");
        if (items != null) {
            visit(items, definitions, activeRefs, depth + 1);
        }
        JsonNode uniqueItems = schema.get("uniqueItems");
        if (uniqueItems != null && !uniqueItems.isBoolean()) {
            throw new IllegalArgumentException("operation schema uniqueItems is invalid");
        }
        JsonNode defs = schema.get("$defs");
        if (defs != null) {
            if (!defs.isObject()) {
                throw new IllegalArgumentException("operation schema $defs must be an object");
            }
            for (JsonNode definition : defs) {
                visit(definition, defs, activeRefs, depth + 1);
            }
        }
    }

    static void requiredFields(JsonNode properties, JsonNode required) {
        if (required == null) {
            return;
        }
        if (!required.isArray()) {
            throw new IllegalArgumentException("operation schema required must be an array");
        }
        Set<String> uniqueRequired = new HashSet<>();
        for (JsonNode name : required) {
            if (!name.isTextual() || properties == null || !properties.has(name.asText())
                    || !uniqueRequired.add(name.asText())) {
                throw new IllegalArgumentException("operation schema required field is not declared");
            }
        }
    }

    static void localReference(
            JsonNode schema, JsonNode definitions, Set<String> activeRefs, int depth) {
        JsonNode reference = schema.get("$ref");
        if (!reference.isTextual() || !reference.asText().startsWith("#/$defs/")) {
            throw new IllegalArgumentException("operation schema references must be local");
        }
        Iterator<String> siblings = schema.fieldNames();
        while (siblings.hasNext()) {
            String sibling = siblings.next();
            if (!sibling.equals("$ref")
                    && !(depth == 0 && (sibling.equals("$schema") || sibling.equals("$defs")))) {
                throw new IllegalArgumentException("operation schema $ref siblings are unsupported");
            }
        }
        String name = reference.asText().substring("#/$defs/".length());
        if (name.isBlank() || definitions == null || !definitions.has(name)
                || !activeRefs.add(reference.asText())) {
            throw new IllegalArgumentException("operation schema reference is missing or recursive");
        }
        try {
            visit(definitions.get(name), definitions, activeRefs, depth + 1);
        } finally {
            activeRefs.remove(reference.asText());
        }
    }

    static void type(JsonNode type) {
        if (type == null) {
            return;
        }
        if (type.isTextual()) {
            if (!TYPES.contains(type.asText())) {
                throw new IllegalArgumentException("operation schema type is unsupported");
            }
            return;
        }
        if (!type.isArray() || type.isEmpty() || type.size() > TYPES.size()) {
            throw new IllegalArgumentException("operation schema type union is invalid");
        }
        Set<String> unique = new HashSet<>();
        for (JsonNode value : type) {
            if (!value.isTextual() || !TYPES.contains(value.asText()) || !unique.add(value.asText())) {
                throw new IllegalArgumentException("operation schema type union is invalid");
            }
        }
    }

    static String structuralType(JsonNode type, JsonNode value) {
        if (type == null || type.isTextual()) {
            return type == null ? null : type.asText();
        }
        String desired = null;
        if (value.isObject()) {
            desired = "object";
        } else if (value.isArray()) {
            desired = "array";
        }
        if (desired != null) {
            for (JsonNode option : type) {
                if (desired.equals(option.asText())) {
                    return desired;
                }
            }
        }
        return null;
    }

    static void enumValues(JsonNode values) {
        if (values != null && (!values.isArray() || values.isEmpty())) {
            throw new IllegalArgumentException("operation schema enum is invalid");
        }
    }

    static void bounds(JsonNode schema) {
        for (String name : Set.of("minItems", "maxItems", "minLength", "maxLength")) {
            JsonNode value = schema.get(name);
            if (value != null && (!value.isIntegralNumber() || value.asLong() < 0 || value.asLong() > 1_000_000)) {
                throw new IllegalArgumentException("operation schema bound is invalid: " + name);
            }
        }
        for (String name : Set.of("minimum", "maximum")) {
            JsonNode value = schema.get(name);
            if (value != null && !OperationSchemaValueSemantics.isJsonNumber(value)) {
                throw new IllegalArgumentException("operation schema numeric bound is invalid: " + name);
            }
        }
        JsonNode pattern = schema.get("pattern");
        if (pattern != null) {
            if (!pattern.isTextual() || pattern.asText().length() > 512) {
                throw new IllegalArgumentException("operation schema pattern is invalid");
            }
            try {
                Pattern.compile(pattern.asText());
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("operation schema pattern is invalid", exception);
            }
            OperationSchemaPatternSafety.validate(pattern.asText());
        }
        JsonNode minimum = schema.get("minimum");
        JsonNode maximum = schema.get("maximum");
        if (minimum != null && maximum != null
                && OperationSchemaValueSemantics.compareNumbers(minimum, maximum) > 0) {
            throw new IllegalArgumentException("operation schema numeric bounds are inverted");
        }
        JsonNode minItems = schema.get("minItems");
        JsonNode maxItems = schema.get("maxItems");
        if (minItems != null && maxItems != null && minItems.asLong() > maxItems.asLong()) {
            throw new IllegalArgumentException("operation schema item bounds are inverted");
        }
        JsonNode minLength = schema.get("minLength");
        JsonNode maxLength = schema.get("maxLength");
        if (minLength != null && maxLength != null && minLength.asLong() > maxLength.asLong()) {
            throw new IllegalArgumentException("operation schema string bounds are inverted");
        }
    }

}
