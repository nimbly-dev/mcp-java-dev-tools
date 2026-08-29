package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/** Produces stable field ordering for export identifier input. */
final class ExecutionExportCanonicalJson {

    private ExecutionExportCanonicalJson() {
    }

    static JsonNode order(JsonNode input) {
        if (input == null || input.isValueNode()) {
            return input;
        }
        if (input.isObject()) {
            ObjectNode ordered = JsonNodeFactory.instance.objectNode();
            List<String> fields = new ArrayList<>();
            input.fieldNames().forEachRemaining(fields::add);
            fields.sort(String::compareTo);
            for (String field : fields) {
                ordered.set(field, order(input.get(field)));
            }
            return ordered;
        }
        ArrayNode ordered = JsonNodeFactory.instance.arrayNode();
        input.forEach(child -> ordered.add(order(child)));
        return ordered;
    }
}
