package com.nimbly.mcpjavadevtools.server.core.operation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** Small bounded builder for explicit operation input and result schemas. */
public final class CanonicalOperationSchema {

    private CanonicalOperationSchema() {
    }

    public static ObjectNode object() {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("type", "object");
        value.put("additionalProperties", false);
        value.putObject("properties");
        return value;
    }

    public static ObjectNode openObject() {
        ObjectNode value = object();
        value.put("additionalProperties", true);
        return value;
    }

    public static ObjectNode property(ObjectNode parent, String name) {
        return parent.with("properties").putObject(name);
    }

    public static ObjectNode string(ObjectNode parent, String name) {
        return property(parent, name).put("type", "string");
    }

    public static ObjectNode integer(ObjectNode parent, String name) {
        return property(parent, name).put("type", "integer");
    }

    public static ObjectNode booleanValue(ObjectNode parent, String name) {
        return property(parent, name).put("type", "boolean");
    }

    public static ObjectNode enumString(ObjectNode parent, String name, String... values) {
        ObjectNode value = string(parent, name);
        ArrayNode allowed = value.putArray("enum");
        for (String entry : values) {
            allowed.add(entry);
        }
        return value;
    }

    public static ObjectNode arrayOfStrings(ObjectNode parent, String name) {
        ObjectNode value = property(parent, name).put("type", "array");
        value.putObject("items").put("type", "string");
        return value;
    }

    public static OperationSchema schema(ObjectNode definition) {
        definition.put("$schema", OperationSchemaRules.DRAFT_2020_12);
        return new OperationSchema(definition);
    }

    public static void required(ObjectNode object, String... names) {
        ArrayNode values = object.putArray("required");
        for (String name : names) {
            values.add(name);
        }
    }
}
