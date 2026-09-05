package com.nimbly.mcpjavadevtools.server.core.operation.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;

/** Implements JSON Schema value equality, numeric comparison, and Unicode lengths. */
public class OperationSchemaValueSemantics {

    private OperationSchemaValueSemantics() {
    }

    static boolean equalsValue(
            JsonNode left, JsonNode right, OperationValidationBudget budget) {
        if (budget.expired()) {
            return false;
        }
        if ((left != null && left.isNumber()) || (right != null && right.isNumber())) {
            return left != null && right != null && left.isNumber()
                    && right.isNumber() && numericEquals(left, right);
        }
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.isNull() || right.isNull()) {
            return left != null && right != null && left.isNull() && right.isNull();
        }
        if (left.isArray() || right.isArray()) {
            if (!left.isArray() || !right.isArray() || left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!equalsValue(left.get(index), right.get(index), budget)) {
                    return false;
                }
            }
            return true;
        }
        if (left.isObject() || right.isObject()) {
            if (!left.isObject() || !right.isObject() || left.size() != right.size()) {
                return false;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = left.fields();
            while (fields.hasNext()) {
                if (budget.expired()) {
                    return false;
                }
                Map.Entry<String, JsonNode> field = fields.next();
                if (!right.has(field.getKey())
                        || !equalsValue(field.getValue(), right.get(field.getKey()), budget)) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    static boolean isInteger(JsonNode value) {
        if (value == null || !value.isNumber()) {
            return false;
        }
        try {
            return value.decimalValue().stripTrailingZeros().scale() <= 0;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static boolean isJsonNumber(JsonNode value) {
        if (value == null || !value.isNumber()) {
            return false;
        }
        try {
            value.decimalValue();
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static int compareNumbers(JsonNode left, JsonNode right) {
        BigDecimal leftValue = left.decimalValue();
        BigDecimal rightValue = right.decimalValue();
        return leftValue.compareTo(rightValue);
    }

    static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    static int hashValue(JsonNode value, OperationValidationBudget budget) {
        if (budget.expired() || value == null || value.isNull()) {
            return 0;
        }
        if (value.isNumber()) {
            try {
                return value.decimalValue().stripTrailingZeros().hashCode();
            } catch (RuntimeException exception) {
                return 0;
            }
        }
        if (value.isArray()) {
            int hash = 1;
            for (JsonNode item : value) {
                if (budget.expired()) {
                    return 0;
                }
                hash = 31 * hash + hashValue(item, budget);
            }
            return hash;
        }
        if (value.isObject()) {
            int hash = 0;
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                if (budget.expired()) {
                    return 0;
                }
                Map.Entry<String, JsonNode> field = fields.next();
                hash += field.getKey().hashCode() ^ hashValue(field.getValue(), budget);
            }
            return hash;
        }
        return value.hashCode();
    }

    private static boolean numericEquals(JsonNode left, JsonNode right) {
        return isJsonNumber(left) && isJsonNumber(right)
                && compareNumbers(left, right) == 0;
    }
}
