package com.nimbly.mcpjavadevtools.server.mcp.tools.routesynthesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Builds the action-discriminated public schema for route_synthesis. */
public class RouteSynthesisMcpSchema {

    private RouteSynthesisMcpSchema() {
    }

    /** Returns the public schema whose action const and input shape are correlated branches. */
    public static String publicInputSchema(ObjectMapper objectMapper) {
        return build(objectMapper);
    }

    private static String build(ObjectMapper objectMapper) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode rootProperties = root.putObject("properties");
        enumString(rootProperties, "action", "infer_target", "class_methods",
                "discover_handlers", "create_recipe");
        rootProperties.putObject("input").put("type", "object");
        required(root, "action", "input");
        ArrayNode branches = root.putArray("oneOf");
        addTargetBranch(branches, objectMapper, "infer_target");
        addTargetBranch(branches, objectMapper, "class_methods");
        addTargetBranch(branches, objectMapper, "discover_handlers");
        addRecipeBranch(branches, objectMapper);
        return root.toPrettyString();
    }

    private static void addTargetBranch(ArrayNode branches, ObjectMapper mapper, String action) {
        ObjectNode branch = branches.addObject();
        branch.put("type", "object");
        branch.put("additionalProperties", false);
        ObjectNode properties = branch.putObject("properties");
        properties.putObject("action").put("const", action);
        properties.set("input", targetInput(mapper));
        required(branch, "action", "input");
    }

    private static void addRecipeBranch(ArrayNode branches, ObjectMapper mapper) {
        ObjectNode branch = branches.addObject();
        branch.put("type", "object");
        branch.put("additionalProperties", false);
        ObjectNode properties = branch.putObject("properties");
        properties.putObject("action").put("const", "create_recipe");
        properties.set("input", recipeInput(mapper));
        required(branch, "action", "input");
    }

    private static ObjectNode targetInput(ObjectMapper mapper) {
        ObjectNode input = mapper.createObjectNode();
        input.put("type", "object");
        input.put("additionalProperties", false);
        ObjectNode properties = input.putObject("properties");
        string(properties, "projectRootAbs");
        arrayOfStrings(properties, "additionalSourceRoots");
        string(properties, "classHint");
        string(properties, "methodHint");
        positiveInteger(properties, "lineHint");
        positiveInteger(properties, "maxCandidates");
        string(properties, "probeId");
        string(properties, "probeBaseUrl");
        required(input, "projectRootAbs");
        return input;
    }

    private static ObjectNode recipeInput(ObjectMapper mapper) {
        ObjectNode input = targetInput(mapper);
        ObjectNode properties = (ObjectNode) input.get("properties");
        properties.remove("maxCandidates");
        string(properties, "mappingsBaseUrl");
        enumString(properties, "discoveryPreference", "static_only", "runtime_first", "runtime_only");
        string(properties, "apiBasePath");
        enumString(properties, "intentMode", "line_probe", "regression");
        string(properties, "authToken");
        string(properties, "authUsername");
        string(properties, "authPassword");
        properties.putObject("actuationEnabled").put("type", "boolean");
        properties.putObject("actuationReturnBoolean").put("type", "boolean");
        string(properties, "actuationActuatorId");
        string(properties, "outputTemplate");
        required(input, "projectRootAbs", "classHint", "methodHint", "intentMode");
        return input;
    }

    private static void string(ObjectNode properties, String name) {
        properties.putObject(name).put("type", "string");
    }

    private static void arrayOfStrings(ObjectNode properties, String name) {
        properties.putObject(name).put("type", "array")
                .put("maxItems", 10)
                .putObject("items").put("type", "string");
    }

    private static void positiveInteger(ObjectNode properties, String name) {
        properties.putObject(name).put("type", "integer").put("minimum", 1);
    }

    private static void enumString(ObjectNode properties, String name, String... values) {
        ObjectNode property = properties.putObject(name).put("type", "string");
        ArrayNode allowed = property.putArray("enum");
        for (String value : values) {
            allowed.add(value);
        }
    }

    private static void required(ObjectNode object, String... fields) {
        ArrayNode required = object.putArray("required");
        for (String field : fields) {
            required.add(field);
        }
    }
}
