package com.nimbly.mcpjavadevtools.server.mcp.tools.operation.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Defensive immutable copies for JSON values exposed by the CDE tool responses. */
final class OperationMcpJsonValue {

    private OperationMcpJsonValue() {
    }

    static Object immutableCopy(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> copy = new LinkedHashMap<>();
            source.forEach((key, nested) -> {
                if (key instanceof String name) {
                    copy.put(name, immutableCopy(nested));
                }
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> source) {
            List<Object> copy = new ArrayList<>(source.size());
            source.forEach(nested -> copy.add(immutableCopy(nested)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
