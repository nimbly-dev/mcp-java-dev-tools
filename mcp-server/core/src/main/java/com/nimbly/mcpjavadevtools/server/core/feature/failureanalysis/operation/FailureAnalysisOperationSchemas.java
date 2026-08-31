package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.operation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.FailureAnalysisAction;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CanonicalOperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;

/** Builds the typed request schemas owned by the Failure Analysis operations. */
final class FailureAnalysisOperationSchemas {

    private FailureAnalysisOperationSchemas() {
    }

    static OperationSchema forAction(FailureAnalysisAction action) {
        return action == FailureAnalysisAction.ANALYZE_TRACE ? analyzeSchema() : verifySchema();
    }

    static OperationSchema analyzeSchema() {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "trace");
        root.with("properties").with("trace").put("minLength", 1).put("maxLength", 200000)
                .put("pattern", "\\S");
        CanonicalOperationSchema.string(root, "sidecarBaseUrl").put("minLength", 1)
                .put("pattern", "\\S");
        CanonicalOperationSchema.string(root, "sidecarAuthorization");
        root.with("properties").with("sidecarAuthorization").put("minLength", 1)
                .put("maxLength", 8192).put("pattern", "\\S");
        root.with("properties").set("investigation", investigationSchema());
        root.with("properties").putObject("timeoutMs")
                .put("type", "integer").put("minimum", 1000).put("maximum", 30000);
        CanonicalOperationSchema.required(root, "trace", "sidecarBaseUrl");
        return CanonicalOperationSchema.schema(root);
    }

    static OperationSchema verifySchema() {
        ObjectNode root = verificationRootSchema();
        ObjectNode expected = expectedFingerprintSchema();
        ObjectNode lineHit = lineHitSchema();
        ObjectNode terminal = terminalSchema();
        ObjectNode investigation = investigationSchema();
        root.with("properties").set("expectedFingerprint", expected.deepCopy());
        root.with("properties").set("lineHit", lineHit.deepCopy());
        root.with("properties").set("terminalState", terminal.deepCopy());
        root.with("properties").set("investigation", investigation.deepCopy());
        root.putArray("oneOf")
                .add(runtimeSchema(root, expected, lineHit, investigation))
                .add(terminalVariantSchema(terminal, investigation));
        return CanonicalOperationSchema.schema(root);
    }

    static ObjectNode verificationRootSchema() {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "captureId").put("minLength", 1).put("pattern", "\\S");
        CanonicalOperationSchema.string(root, "sidecarBaseUrl").put("minLength", 1)
                .put("pattern", "\\S");
        CanonicalOperationSchema.string(root, "sidecarAuthorization");
        root.with("properties").with("sidecarAuthorization").put("minLength", 1)
                .put("maxLength", 8192).put("pattern", "\\S");
        root.with("properties").putObject("timeoutMs")
                .put("type", "integer").put("minimum", 1000).put("maximum", 30000);
        return root;
    }

    static ObjectNode expectedFingerprintSchema() {
        ObjectNode expected = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(expected, "exceptionType").put("minLength", 1)
                .put("pattern", "\\S");
        CanonicalOperationSchema.string(expected, "rootCauseType").put("minLength", 1)
                .put("pattern", "\\S");
        CanonicalOperationSchema.string(expected, "nearestApplicationMethodKey")
                .put("minLength", 1).put("pattern", "\\S");
        CanonicalOperationSchema.required(expected, "exceptionType", "rootCauseType",
                "nearestApplicationMethodKey");
        return expected;
    }

    static ObjectNode lineHitSchema() {
        ObjectNode lineHit = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(lineHit, "strictLineKey").put("minLength", 1)
                .put("pattern", "\\S");
        lineHit.with("properties").putObject("hitCount").put("type", "integer").put("minimum", 1);
        CanonicalOperationSchema.required(lineHit, "strictLineKey", "hitCount");
        return lineHit;
    }

    static ObjectNode runtimeSchema(
            ObjectNode root, ObjectNode expected, ObjectNode lineHit, ObjectNode investigation) {
        ObjectNode runtime = CanonicalOperationSchema.object();
        runtime.with("properties").putObject("captureId").put("type", "string");
        runtime.with("properties").set("expectedFingerprint", expected.deepCopy());
        runtime.with("properties").set("lineHit", lineHit.deepCopy());
        runtime.with("properties").set("sidecarBaseUrl",
                root.path("properties").path("sidecarBaseUrl").deepCopy());
        runtime.with("properties").set("sidecarAuthorization",
                root.path("properties").path("sidecarAuthorization").deepCopy());
        runtime.with("properties").putObject("timeoutMs").put("type", "integer")
                .put("minimum", 1000).put("maximum", 30000);
        runtime.with("properties").set("investigation", investigation.deepCopy());
        CanonicalOperationSchema.required(runtime, "captureId", "expectedFingerprint", "lineHit",
                "sidecarBaseUrl");
        return runtime;
    }

    static ObjectNode terminalVariantSchema(
            ObjectNode terminal, ObjectNode investigation) {
        ObjectNode variant = CanonicalOperationSchema.object();
        variant.with("properties").set("terminalState", terminal.deepCopy());
        variant.with("properties").set("investigation", investigation.deepCopy());
        CanonicalOperationSchema.required(variant, "terminalState");
        return variant;
    }

    static ObjectNode investigationSchema() {
        ObjectNode investigation = CanonicalOperationSchema.object();
        CanonicalOperationSchema.enumString(investigation, "mode", "guided", "hands_off");
        investigation.with("properties").putObject("attemptLimit")
                .put("type", "integer").put("minimum", 1).put("maximum", 10);
        investigation.with("properties").putObject("elapsedTimeLimitMs")
                .put("type", "integer").put("minimum", 1000).put("maximum", 300000);
        CanonicalOperationSchema.required(investigation, "mode", "attemptLimit", "elapsedTimeLimitMs");
        return investigation;
    }

    static ObjectNode terminalSchema() {
        ObjectNode terminal = CanonicalOperationSchema.object();
        CanonicalOperationSchema.enumString(terminal, "outcome", "BLOCKED_AMBIGUOUS_JVM", "BLOCKED_MISSING_AUTH",
                "BLOCKED_MISSING_TRIGGER", "BLOCKED_USER_ACTION_REQUIRED", "BLOCKED_UNSAFE_OPERATION",
                "ENVIRONMENT_MISMATCH", "INCONCLUSIVE", "CANCELLED");
        CanonicalOperationSchema.string(terminal, "reasonCode").put("minLength", 1)
                .put("maxLength", 120).put("pattern", "\\S");
        CanonicalOperationSchema.enumString(terminal, "cleanupStatus",
                "cleanup_confirmed", "cleanup_incomplete", "external_workflow_owned");
        terminal.with("properties").putObject("attemptCount").put("type", "integer")
                .put("minimum", 0).put("maximum", 10);
        CanonicalOperationSchema.required(terminal, "outcome", "reasonCode", "cleanupStatus", "attemptCount");
        return terminal;
    }
}
