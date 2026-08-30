package com.nimbly.mcpjavadevtools.server.core.operation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;

/** Explicit per-action JSON schemas for the released Probe request surface. */
final class ProbeOperationSchemas {

    private ProbeOperationSchemas() {
    }

    static OperationSchema schema(ProbeAction action) {
        return switch (action) {
            case CHECK -> check();
            case STATUS -> status();
            case RESET -> reset();
            case WAIT_FOR_HIT -> waitForHit();
            case CAPTURE -> capture();
            case ACTUATE -> actuate();
            case PROFILER -> profiler();
        };
    }

    static OperationSchema check() {
        ObjectNode root = CanonicalOperationSchema.object();
        target(root);
        http(root);
        timeout(root);
        return CanonicalOperationSchema.schema(root);
    }

    static OperationSchema status() {
        ObjectNode root = CanonicalOperationSchema.object();
        selectors(root);
        target(root);
        timeout(root);
        return CanonicalOperationSchema.schema(root);
    }

    static OperationSchema reset() {
        ObjectNode root = CanonicalOperationSchema.object();
        selectors(root);
        CanonicalOperationSchema.string(root, "className").put("minLength", 1);
        target(root);
        timeout(root);
        return CanonicalOperationSchema.schema(root);
    }

    static OperationSchema waitForHit() {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "key");
        root.with("properties").putObject("lineHint")
                .put("type", "integer").put("minimum", 1);
        target(root);
        timeout(root);
        root.with("properties").putObject("pollIntervalMs")
                .put("type", "integer").put("minimum", 1);
        root.with("properties").putObject("maxRetries")
                .put("type", "integer").put("minimum", 1);
        CanonicalOperationSchema.required(root, "key");
        return CanonicalOperationSchema.schema(root);
    }

    static OperationSchema capture() {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "captureId").put("minLength", 1);
        target(root);
        timeout(root);
        CanonicalOperationSchema.required(root, "captureId");
        return CanonicalOperationSchema.schema(root);
    }

    static OperationSchema actuate() {
        ObjectNode root = CanonicalOperationSchema.object();
        target(root);
        CanonicalOperationSchema.enumString(root, "action", "arm", "disarm");
        CanonicalOperationSchema.string(root, "sessionId").put("minLength", 1);
        CanonicalOperationSchema.string(root, "actuatorId");
        CanonicalOperationSchema.string(root, "targetKey");
        root.with("properties").putObject("returnBoolean").put("type", "boolean");
        root.with("properties").putObject("ttlMs").put("type", "integer").put("minimum", 1);
        timeout(root);
        CanonicalOperationSchema.required(root, "action", "sessionId");
        return CanonicalOperationSchema.schema(root);
    }

    static OperationSchema profiler() {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.enumString(root, "action", "start", "stop", "reset", "status", "download");
        CanonicalOperationSchema.string(root, "sessionId").put("minLength", 1);
        CanonicalOperationSchema.enumString(root, "provider", "auto", "async-profiler", "jfr");
        CanonicalOperationSchema.string(root, "event").put("minLength", 1);
        root.with("properties").putObject("intervalNanos")
                .put("type", "integer").put("minimum", 1);
        CanonicalOperationSchema.string(root, "outputPath").put("minLength", 1);
        CanonicalOperationSchema.enumString(root, "outputFormat", "jfr");
        target(root);
        timeout(root);
        CanonicalOperationSchema.required(root, "action");
        return CanonicalOperationSchema.schema(root);
    }

    static void target(ObjectNode root) {
        CanonicalOperationSchema.string(root, "baseUrl");
        CanonicalOperationSchema.string(root, "probeId").put("minLength", 1);
    }

    static void http(ObjectNode root) {
        ObjectNode http = root.with("properties").putObject("http");
        http.put("type", "object").put("additionalProperties", false);
        http.putObject("properties").putObject("headers")
                .put("type", "object")
                .putObject("additionalProperties").put("type", "string");
    }

    static void timeout(ObjectNode root) {
        root.with("properties").putObject("timeoutMs")
                .put("type", "integer").put("minimum", 1);
    }

    static void selectors(ObjectNode root) {
        CanonicalOperationSchema.string(root, "key").put("minLength", 1);
        ObjectNode keys = root.with("properties").putObject("keys").put("type", "array");
        keys.put("minItems", 1).put("maxItems", 1000).putObject("items")
                .put("type", "string").put("minLength", 1);
        root.with("properties").putObject("lineHint")
                .put("type", "integer").put("minimum", 1);
    }
}
