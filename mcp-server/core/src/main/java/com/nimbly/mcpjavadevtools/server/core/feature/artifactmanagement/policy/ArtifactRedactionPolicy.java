package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounds and redacts Artifact values before they cross the Core output boundary. */
public final class ArtifactRedactionPolicy {

    private static final int MAX_DEPTH = 8;
    private static final int MAX_FIELDS = 100;
    private static final int MAX_ITEMS = 100;
    private static final int MAX_STRING_LENGTH = 4096;
    private static final String REDACTED = "[REDACTED]";

    private ArtifactRedactionPolicy() {
    }

    /** Sanitizes a result map while preserving deterministic insertion order. */
    public static Map<String, Object> sanitizeMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (count++ >= MAX_FIELDS) {
                sanitized.put("_truncated", true);
                break;
            }
            sanitized.put(entry.getKey(), sanitizeValue(entry.getKey(), entry.getValue(), 0));
        }
        return sanitized;
    }

    /** Sanitizes a value before persisting it in a generated export or projection. */
    public static Object sanitizeValue(String field, Object value) {
        return sanitizeValue(field, value, 0);
    }

    /** Sanitizes a JSON node with the same redaction and bounds as result maps. */
    public static JsonNode sanitizeJson(JsonNode value) {
        return sanitizeJson(value, 0, "");
    }

    private static Object sanitizeValue(String field, Object value, int depth) {
        if (isSensitive(field)) {
            return REDACTED;
        }
        if (value instanceof JsonNode node) {
            return sanitizeJson(node, depth, field);
        }
        if (value instanceof Map<?, ?> map) {
            if (depth >= MAX_DEPTH) {
                return "[DEPTH_LIMIT]";
            }
            Map<String, Object> output = new LinkedHashMap<>();
            int count = 0;
            for (var entry : map.entrySet()) {
                if (count++ >= MAX_FIELDS) {
                    output.put("_truncated", true);
                    break;
                }
                String key = String.valueOf(entry.getKey());
                output.put(key, sanitizeValue(key, entry.getValue(), depth + 1));
            }
            return output;
        }
        if (value instanceof List<?> list) {
            return sanitizeList(field, list, depth);
        }
        if (value instanceof String text && text.length() > MAX_STRING_LENGTH) {
            return text.substring(0, MAX_STRING_LENGTH) + "...[TRUNCATED]";
        }
        return value;
    }

    private static List<Object> sanitizeList(String field, List<?> values, int depth) {
        List<Object> output = new ArrayList<>();
        int limit = Math.min(values.size(), MAX_ITEMS);
        for (int index = 0; index < limit; index++) {
            output.add(sanitizeValue(field, values.get(index), depth + 1));
        }
        if (values.size() > MAX_ITEMS) {
            output.add("[TRUNCATED]");
        }
        return output;
    }

    private static JsonNode sanitizeJson(JsonNode value, int depth, String field) {
        if (isSensitive(field)) {
            return JsonNodeFactory.instance.textNode(REDACTED);
        }
        if (value == null || value.isNull() || value.isValueNode()) {
            if (value != null && value.isTextual() && value.textValue().length() > MAX_STRING_LENGTH) {
                return JsonNodeFactory.instance.textNode(
                        value.textValue().substring(0, MAX_STRING_LENGTH) + "...[TRUNCATED]");
            }
            return value;
        }
        if (depth >= MAX_DEPTH) {
            return JsonNodeFactory.instance.textNode("[DEPTH_LIMIT]");
        }
        if (value.isArray()) {
            ArrayNode output = JsonNodeFactory.instance.arrayNode();
            int limit = Math.min(value.size(), MAX_ITEMS);
            for (int index = 0; index < limit; index++) {
                output.add(sanitizeJson(value.get(index), depth + 1, field));
            }
            if (value.size() > MAX_ITEMS) {
                output.add("[TRUNCATED]");
            }
            return output;
        }
        ObjectNode output = JsonNodeFactory.instance.objectNode();
        int count = 0;
        var fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (count++ >= MAX_FIELDS) {
                output.put("_truncated", true);
                break;
            }
            output.set(entry.getKey(), sanitizeJson(entry.getValue(), depth + 1, entry.getKey()));
        }
        return output;
    }

    private static boolean isSensitive(String field) {
        if (field == null) {
            return false;
        }
        String normalized = field.toLowerCase(java.util.Locale.ROOT);
        if (isSymbolicReference(normalized)) {
            return false;
        }
        return normalized.contains("password") || normalized.contains("secret")
                || normalized.contains("token") || normalized.contains("authorization")
                || normalized.contains("cookie") || normalized.contains("privatekey")
                || normalized.contains("api_key") || normalized.contains("apikey")
                || normalized.contains("credential");
    }

    private static boolean isSymbolicReference(String normalized) {
        return normalized.endsWith("ref") && (normalized.contains("credential")
                || normalized.contains("secret") || normalized.contains("token")
                || normalized.contains("password") || normalized.contains("apikey"));
    }
}
