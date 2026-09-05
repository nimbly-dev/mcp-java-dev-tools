package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;
import java.util.Map;

/** Compares a bounded request snapshot with its typed decoder round trip. */
public class OperationRequestEquivalence {

    private static final int MAX_SCHEMA_REFERENCE_DEPTH = 16;

    private OperationRequestEquivalence() {
    }

    static boolean equivalent(
            JsonNode left,
            JsonNode right,
            Map<String, JsonNode> defaultFields,
            OperationSchema schema) {
        return equivalentAt(left, right, defaultFields, schema.definition(), "", schema.definition());
    }

    static boolean equivalentAt(
            JsonNode left,
            JsonNode right,
            Map<String, JsonNode> defaultFields,
            JsonNode schema,
            String path,
            JsonNode rootSchema) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left.isNumber() || right.isNumber()) {
            return numericEquivalent(left, right);
        }
        if (left.isArray() || right.isArray()) {
            return arrayEquivalent(left, right, defaultFields, schema, path, rootSchema);
        }
        if (left.isObject() || right.isObject()) {
            return objectEquivalent(left, right, defaultFields, schema, path, rootSchema);
        }
        return left.equals(right);
    }

    static boolean numericEquivalent(JsonNode left, JsonNode right) {
        if (!left.isNumber() || !right.isNumber()) {
            return false;
        }
        try {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static boolean arrayEquivalent(
            JsonNode left,
            JsonNode right,
            Map<String, JsonNode> defaultFields,
            JsonNode schema,
            String path,
            JsonNode rootSchema) {
        if (!left.isArray() || !right.isArray() || left.size() != right.size()) {
            return false;
        }
        JsonNode resolvedSchema = resolveSchema(schema, rootSchema);
        JsonNode itemSchema = resolvedSchema == null ? null : resolvedSchema.get("items");
        for (int index = 0; index < left.size(); index++) {
            if (!equivalentAt(left.get(index), right.get(index), defaultFields,
                    itemSchema, path + "/" + index, rootSchema)) {
                return false;
            }
        }
        return true;
    }

    static boolean objectEquivalent(
            JsonNode left,
            JsonNode right,
            Map<String, JsonNode> defaultFields,
            JsonNode schema,
            String path,
            JsonNode rootSchema) {
        if (!left.isObject() || !right.isObject() || left.size() > right.size()) {
            return false;
        }
        var fields = left.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String fieldPath = childPath(path, field.getKey());
            if (!right.has(field.getKey())
                    || !equivalentAt(field.getValue(), right.get(field.getKey()), defaultFields,
                    propertySchema(schema, field.getKey(), rootSchema), fieldPath, rootSchema)) {
                return false;
            }
        }
        var additional = right.fields();
        while (additional.hasNext()) {
            var field = additional.next();
            String fieldPath = childPath(path, field.getKey());
            JsonNode expected = defaultValue(defaultFields, path, field.getKey(), fieldPath);
            boolean nullable = field.getValue().isNull() && nullableSchema(
                    propertySchema(schema, field.getKey(), rootSchema), rootSchema);
            if (!left.has(field.getKey())
                    && (expected == null && !nullable
                    || expected != null && !equivalentAt(
                    expected, field.getValue(), Map.of(), null, "", rootSchema))) {
                return false;
            }
        }
        return true;
    }

    static JsonNode defaultValue(
            Map<String, JsonNode> defaultFields,
            String parentPath,
            String field,
            String fieldPath) {
        JsonNode expected = defaultFields.get(fieldPath);
        if (expected == null && parentPath.isEmpty() && !field.startsWith("/")) {
            return defaultFields.get(field);
        }
        return expected;
    }

    static String childPath(String path, String field) {
        return path + "/" + field.replace("~", "~0").replace("/", "~1");
    }

    static String canonicalDefaultPath(String path) {
        if (path.startsWith("/")) {
            validatePointer(path);
            return path;
        }
        return childPath("", path);
    }

    static void validatePointer(String path) {
        for (int index = 1; index < path.length(); index++) {
            if (path.charAt(index) == '~') {
                if (index + 1 >= path.length()
                        || (path.charAt(index + 1) != '0' && path.charAt(index + 1) != '1')) {
                    throw new IllegalArgumentException("operation default pointer is malformed");
                }
                index++;
            }
        }
    }

    static JsonNode propertySchema(JsonNode schema, String field, JsonNode rootSchema) {
        if (schema == null) {
            return null;
        }
        JsonNode resolved = resolveSchema(schema, rootSchema);
        if (resolved == null) {
            return null;
        }
        JsonNode properties = resolved.get("properties");
        if (properties != null && properties.has(field)) {
            return properties.get(field);
        }
        JsonNode alternatives = resolved.get("oneOf");
        if (alternatives != null) {
            for (JsonNode alternative : alternatives) {
                JsonNode candidate = propertySchema(alternative, field, rootSchema);
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return null;
    }

    static JsonNode resolveSchema(JsonNode schema, JsonNode rootSchema) {
        JsonNode current = schema;
        for (int depth = 0; current != null && depth < MAX_SCHEMA_REFERENCE_DEPTH; depth++) {
            JsonNode reference = current.get("$ref");
            if (reference == null || !reference.isTextual()
                    || !reference.asText().startsWith("#/$defs/")) {
                return current;
            }
            JsonNode definitions = rootSchema == null ? null : rootSchema.get("$defs");
            if (definitions == null) {
                return current;
            }
            current = definitions.get(reference.asText().substring("#/$defs/".length()));
        }
        return current;
    }

    static boolean nullableSchema(JsonNode schema, JsonNode rootSchema) {
        if (schema == null) {
            return false;
        }
        JsonNode resolved = resolveSchema(schema, rootSchema);
        if (resolved == null) {
            return false;
        }
        JsonNode type = resolved.get("type");
        if (type != null && type.isTextual() && "null".equals(type.asText())) {
            return true;
        }
        if (type != null && type.isArray()) {
            for (JsonNode value : type) {
                if ("null".equals(value.asText())) {
                    return true;
                }
            }
        }
        JsonNode alternatives = resolved.get("oneOf");
        if (alternatives != null) {
            for (JsonNode alternative : alternatives) {
                if (nullableSchema(alternative, rootSchema)) {
                    return true;
                }
            }
        }
        return false;
    }
}
