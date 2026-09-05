package com.nimbly.mcpjavadevtools.server.core.operation.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationValidationBudget;
import java.util.Iterator;
import java.util.Map;

/** Applies result redaction while stopping before a supplied execution budget expires. */
public class BoundedOperationValueRedactor {

    private static final String REDACTED = "***REDACTED***";

    private BoundedOperationValueRedactor() {
    }

    /** Returns a redacted copy, or null when the cooperative budget expires. */
    public static JsonNode redact(
            JsonNode value, String policy, OperationValidationBudget budget) {
        if (budget == null) {
            throw new IllegalArgumentException("redaction budget must not be null");
        }
        return copy(value, policy, budget);
    }

    private static JsonNode copy(JsonNode value, String policy, OperationValidationBudget budget) {
        if (budget.expired()) {
            return null;
        }
        if (value == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (value.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                if (budget.expired()) {
                    return null;
                }
                Map.Entry<String, JsonNode> field = fields.next();
                boolean redact = !"none".equalsIgnoreCase(policy)
                        && !"allow_all".equalsIgnoreCase(policy)
                        && OperationValueRedactor.sensitive(field.getKey());
                JsonNode child = redact
                        ? JsonNodeFactory.instance.textNode(REDACTED)
                        : copy(field.getValue(), policy, budget);
                if (child == null) {
                    return null;
                }
                result.set(field.getKey(), child);
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : value) {
                JsonNode child = copy(item, policy, budget);
                if (child == null) {
                    return null;
                }
                result.add(child);
            }
            return result;
        }
        return value.deepCopy();
    }
}
