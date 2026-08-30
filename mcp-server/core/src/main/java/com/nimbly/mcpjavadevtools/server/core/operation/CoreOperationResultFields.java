package com.nimbly.mcpjavadevtools.server.core.operation;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Named builders for the stable top-level fields emitted by Core results. */
final class CoreOperationResultFields {

    private CoreOperationResultFields() {
    }

    static void string(ObjectNode root, String name) {
        CanonicalOperationSchema.string(root, name);
    }

    static void integer(ObjectNode root, String name) {
        CanonicalOperationSchema.integer(root, name);
    }

    static void nullableString(ObjectNode root, String name) {
        root.with("properties").putObject(name).putArray("type").add("string").add("null");
    }

    static void nullableInteger(ObjectNode root, String name) {
        root.with("properties").putObject(name).putArray("type").add("integer").add("null");
    }

    static void nullableBoolean(ObjectNode root, String name) {
        root.with("properties").putObject(name).putArray("type").add("boolean").add("null");
    }

    static void nullableObject(ObjectNode root, String name) {
        ObjectNode property = root.with("properties").putObject(name);
        property.putArray("type").add("object").add("null");
        property.put("additionalProperties", true);
    }

    static void openObject(ObjectNode root, String name) {
        root.with("properties").putObject(name).put("type", "object").put("additionalProperties", true);
    }

    static void arrayOfStrings(ObjectNode root, String name) {
        CanonicalOperationSchema.arrayOfStrings(root, name);
    }

    static void arrayOfObjects(ObjectNode root, String name) {
        root.with("properties").putObject(name).put("type", "array")
                .putObject("items").put("type", "object").put("additionalProperties", true);
    }
}
