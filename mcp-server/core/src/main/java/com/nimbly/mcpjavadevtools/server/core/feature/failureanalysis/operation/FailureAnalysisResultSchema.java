package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.operation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CanonicalOperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;

/** Canonical result contract shared by the Failure Analysis operations. */
final class FailureAnalysisResultSchema {

    private FailureAnalysisResultSchema() {
    }

    static OperationSchema create() {
        ObjectNode root = CanonicalOperationSchema.openObject();
        CanonicalOperationSchema.string(root, "outcome");
        CanonicalOperationSchema.string(root, "reasonCode");
        CanonicalOperationSchema.string(root, "message");
        nullable(root, "diagnosisClaimed", "boolean");
        nullableOpenObject(root, "fingerprint");
        nullableOpenObject(root, "expectedFingerprint");
        nullableOpenObject(root, "observedFingerprint");
        nullableOpenObject(root, "lineHit");
        nullableOpenObject(root, "attemptEvidence");
        nullable(root, "cleanupStatus", "string");
        nullableOpenObject(root, "investigation");
        arrayOfOpenObjects(root, "investigationCandidates");
        nullableOpenObject(root, "dependencyBoundary");
        arrayOfOpenObjects(root, "exceptionSections");
        CanonicalOperationSchema.arrayOfStrings(root, "incompleteReasons");
        nullable(root, "httpStatus", "integer");
        CanonicalOperationSchema.required(root, "outcome", "reasonCode", "message",
                "investigationCandidates", "exceptionSections", "incompleteReasons");
        return CanonicalOperationSchema.schema(root);
    }

    static void nullable(ObjectNode root, String name, String type) {
        root.with("properties").putObject(name).putArray("type").add(type).add("null");
    }

    static void nullableOpenObject(ObjectNode root, String name) {
        ObjectNode value = root.with("properties").putObject(name);
        value.putArray("type").add("object").add("null");
        value.put("additionalProperties", true);
    }

    static void arrayOfOpenObjects(ObjectNode root, String name) {
        root.with("properties").putObject(name).put("type", "array")
                .putObject("items").put("type", "object").put("additionalProperties", true);
    }
}
