package com.nimbly.mcpjavadevtools.server.core.operation.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyLimits;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Iterative structural guard for already materialized Core JSON payloads. */
public class OperationJsonTreeLimits {

    private OperationJsonTreeLimits() {
    }

    /** Returns one deterministic violation when a payload exceeds a Core hard ceiling. */
    public static List<String> violations(JsonNode value) {
        return violations(value, () -> false);
    }

    /** Returns one deterministic violation while consulting a cooperative budget. */
    static List<String> violations(JsonNode value, OperationValidationBudget budget) {
        if (budget == null) {
            throw new IllegalArgumentException("JSON validation budget must not be null");
        }
        JsonNode root = value == null ? NullNode.getInstance() : value;
        List<String> rootViolation = nodeViolation(root, 1, 1, budget);
        if (!rootViolation.isEmpty()) {
            return rootViolation;
        }
        return childViolations(root, budget);
    }

    static List<String> childViolations(
            JsonNode root, OperationValidationBudget budget) {
        Deque<Iterator<JsonNode>> children = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        Deque<JsonNode> ancestors = new ArrayDeque<>();
        Set<JsonNode> activeContainers = Collections.newSetFromMap(new IdentityHashMap<>());
        children.push(root.elements());
        depths.push(2);
        ancestors.push(root);
        if (root.isContainerNode()) {
            activeContainers.add(root);
        }
        int count = 1;
        while (!children.isEmpty()) {
            if (budget.expired()) {
                return List.of("$ JSON validation budget expired");
            }
            Iterator<JsonNode> iterator = children.peek();
            if (!iterator.hasNext()) {
                children.pop();
                depths.pop();
                JsonNode owner = ancestors.pop();
                activeContainers.remove(owner);
                continue;
            }
            JsonNode current = iterator.next();
            int depth = depths.peek();
            if (current.isContainerNode() && activeContainers.contains(current)) {
                return List.of("$ contains cyclic JSON");
            }
            count++;
            List<String> violation = nodeViolation(current, depth, count, budget);
            if (!violation.isEmpty()) {
                return violation;
            }
            children.push(current.elements());
            depths.push(depth + 1);
            ancestors.push(current);
            if (current.isContainerNode()) {
                activeContainers.add(current);
            }
        }
        return List.of();
    }

    private static List<String> nodeViolation(
            JsonNode current, int depth, int count, OperationValidationBudget budget) {
        if (!current.isObject() && !current.isArray() && !current.isTextual()
                && !current.isNumber() && !current.isBoolean() && !current.isNull()) {
            return List.of("$ contains a non-canonical JSON node");
        }
        if (depth > OperationSafetyLimits.MAX_JSON_DEPTH) {
            return List.of("$ exceeds the maximum JSON depth");
        }
        if (count > OperationSafetyLimits.MAX_JSON_NODES) {
            return List.of("$ exceeds the maximum JSON node count");
        }
        if (current.isObject()) {
            var names = current.fieldNames();
            while (names.hasNext()) {
                if (budget.expired()) {
                    return List.of("$ JSON validation budget expired");
                }
                if (utf8Length(names.next(), budget) < 0) {
                    return budget.expired()
                            ? List.of("$ JSON validation budget expired")
                            : List.of("$ exceeds the maximum UTF-8 string value size");
                }
            }
        }
        if (current.isTextual()
                && utf8Length(current.textValue(), budget) < 0) {
            return budget.expired()
                    ? List.of("$ JSON validation budget expired")
                    : List.of("$ exceeds the maximum UTF-8 string value size");
        }
        if (budget.expired()) {
            return List.of("$ JSON validation budget expired");
        }
        if (current.isNumber() && !OperationSchemaValueSemantics.isJsonNumber(current)) {
            return List.of("$ contains a non-finite numeric value");
        }
        return List.of();
    }

    static long utf8Length(String value) {
        return utf8Length(value, () -> false);
    }

    static long utf8Length(String value, OperationValidationBudget budget) {
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            if (budget.expired()) {
                return -1;
            }
            char character = value.charAt(index);
            if (character <= 0x7F) {
                bytes++;
            } else if (character <= 0x7FF) {
                bytes += 2;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
            if (bytes > OperationSafetyLimits.MAX_STRING_VALUE_BYTES) {
                return -1;
            }
        }
        return bytes;
    }
}
