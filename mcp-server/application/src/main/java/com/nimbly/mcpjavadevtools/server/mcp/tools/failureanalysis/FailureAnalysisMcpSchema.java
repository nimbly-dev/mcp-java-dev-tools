package com.nimbly.mcpjavadevtools.server.mcp.tools.failureanalysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Builds the action-correlated public input schema for failure_analysis. */
public final class FailureAnalysisMcpSchema {

    private FailureAnalysisMcpSchema() {
    }

    /** @return JSON Schema string for both supported actions and verification variants */
    public static String publicInputSchema(ObjectMapper objectMapper) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");
        enumString(properties, "action", "analyze_trace", "verify_reproduction");
        properties.putObject("input").put("type", "object");
        required(root, "action", "input");
        ArrayNode branches = root.putArray("oneOf");
        branch(branches, "analyze_trace", analyzeInput(objectMapper));
        branch(branches, "verify_reproduction", verifyInput(objectMapper));
        return root.toPrettyString();
    }

    private static void branch(ArrayNode branches, String action, ObjectNode input) {
        ObjectNode branch = branches.addObject();
        branch.put("type", "object");
        branch.put("additionalProperties", false);
        ObjectNode properties = branch.putObject("properties");
        properties.putObject("action").put("const", action);
        properties.set("input", input);
        required(branch, "action", "input");
    }

    private static ObjectNode analyzeInput(ObjectMapper mapper) {
        ObjectNode input = baseInput(mapper);
        ObjectNode properties = (ObjectNode) input.get("properties");
        string(properties, "trace").put("minLength", 1).put("maxLength", 200000);
        uri(properties, "sidecarBaseUrl");
        string(properties, "sidecarAuthorization").put("minLength", 1).put("maxLength", 8192);
        investigation(properties);
        timeout(properties);
        required(input, "trace", "sidecarBaseUrl");
        return input;
    }

    private static ObjectNode verifyInput(ObjectMapper mapper) {
        ObjectNode input = mapper.createObjectNode();
        ArrayNode variants = input.putArray("oneOf");
        variants.add(verifyRuntimeInput(mapper));
        variants.add(verifyTerminalInput(mapper));
        return input;
    }

    private static ObjectNode verifyRuntimeInput(ObjectMapper mapper) {
        ObjectNode input = baseInput(mapper);
        ObjectNode properties = (ObjectNode) input.get("properties");
        string(properties, "captureId").put("minLength", 1);
        ObjectNode expected = properties.putObject("expectedFingerprint").put("type", "object");
        expected.put("additionalProperties", false);
        ObjectNode expectedProperties = expected.putObject("properties");
        string(expectedProperties, "exceptionType");
        string(expectedProperties, "rootCauseType");
        string(expectedProperties, "nearestApplicationMethodKey");
        required(expected, "exceptionType", "rootCauseType", "nearestApplicationMethodKey");
        ObjectNode lineHit = properties.putObject("lineHit").put("type", "object");
        lineHit.put("additionalProperties", false);
        ObjectNode lineProperties = lineHit.putObject("properties");
        string(lineProperties, "strictLineKey");
        lineProperties.putObject("hitCount").put("type", "integer").put("minimum", 1);
        required(lineHit, "strictLineKey", "hitCount");
        uri(properties, "sidecarBaseUrl");
        string(properties, "sidecarAuthorization").put("minLength", 1).put("maxLength", 8192);
        investigation(properties);
        timeout(properties);
        required(input, "captureId", "expectedFingerprint", "lineHit", "sidecarBaseUrl");
        return input;
    }

    private static ObjectNode verifyTerminalInput(ObjectMapper mapper) {
        ObjectNode input = baseInput(mapper);
        ObjectNode properties = (ObjectNode) input.get("properties");
        properties.set("terminalState", terminalState(mapper));
        investigation(properties);
        required(input, "terminalState");
        return input;
    }

    private static ObjectNode baseInput(ObjectMapper mapper) {
        ObjectNode input = mapper.createObjectNode();
        input.put("type", "object");
        input.put("additionalProperties", false);
        input.putObject("properties");
        return input;
    }

    private static ObjectNode terminalState(ObjectMapper mapper) {
        ObjectNode terminal = mapper.createObjectNode().put("type", "object");
        terminal.put("additionalProperties", false);
        ObjectNode properties = terminal.putObject("properties");
        enumString(properties, "outcome", "BLOCKED_AMBIGUOUS_JVM", "BLOCKED_MISSING_AUTH",
                "BLOCKED_MISSING_TRIGGER", "BLOCKED_USER_ACTION_REQUIRED", "BLOCKED_UNSAFE_OPERATION",
                "ENVIRONMENT_MISMATCH", "INCONCLUSIVE", "CANCELLED");
        string(properties, "reasonCode").put("minLength", 1).put("maxLength", 120);
        enumString(properties, "cleanupStatus", "cleanup_confirmed", "cleanup_incomplete", "external_workflow_owned");
        properties.putObject("attemptCount").put("type", "integer").put("minimum", 0).put("maximum", 10);
        required(terminal, "outcome", "reasonCode", "cleanupStatus", "attemptCount");
        return terminal;
    }

    private static void investigation(ObjectNode properties) {
        ObjectNode investigation = properties.putObject("investigation").put("type", "object");
        investigation.put("additionalProperties", false);
        ObjectNode values = investigation.putObject("properties");
        enumString(values, "mode", "guided", "hands_off");
        values.putObject("attemptLimit").put("type", "integer").put("minimum", 1).put("maximum", 10);
        values.putObject("elapsedTimeLimitMs").put("type", "integer").put("minimum", 1000).put("maximum", 300000);
        required(investigation, "mode", "attemptLimit", "elapsedTimeLimitMs");
    }

    private static ObjectNode string(ObjectNode properties, String name) {
        return properties.putObject(name).put("type", "string");
    }

    private static void uri(ObjectNode properties, String name) {
        string(properties, name).put("format", "uri");
    }

    private static void timeout(ObjectNode properties) {
        properties.putObject("timeoutMs").put("type", "integer").put("minimum", 1000).put("maximum", 30000);
    }

    private static void enumString(ObjectNode properties, String name, String... values) {
        ObjectNode property = string(properties, name);
        ArrayNode allowed = property.putArray("enum");
        for (String value : values) {
            allowed.add(value);
        }
    }

    private static void required(ObjectNode object, String... fields) {
        ArrayNode values = object.putArray("required");
        for (String field : fields) {
            values.add(field);
        }
    }
}
