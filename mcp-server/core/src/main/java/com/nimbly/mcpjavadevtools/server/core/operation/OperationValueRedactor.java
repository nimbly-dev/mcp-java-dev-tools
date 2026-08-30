package com.nimbly.mcpjavadevtools.server.core.operation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;

/** Applies the manifest redaction policy to JSON results before size checks. */
public final class OperationValueRedactor {

    private static final String REDACTED = "***REDACTED***";

    private OperationValueRedactor() {
    }

    public static JsonNode redact(JsonNode value, String policy) {
        if (value == null || "none".equalsIgnoreCase(policy)
                || "allow_all".equalsIgnoreCase(policy)) {
            return value == null ? JsonNodeFactory.instance.nullNode() : value.deepCopy();
        }
        if (value.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.set(field.getKey(), sensitive(field.getKey())
                        ? JsonNodeFactory.instance.textNode(REDACTED)
                        : redact(field.getValue(), policy));
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : value) {
                result.add(redact(item, policy));
            }
            return result;
        }
        return value.deepCopy();
    }

    static boolean sensitive(String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("password") || normalized.contains("secret")
                || normalized.contains("token") || normalized.contains("credential")
                || normalized.contains("authorization") || normalized.contains("private_key")
                || normalized.equals("apikey") || normalized.equals("api_key");
    }
}
