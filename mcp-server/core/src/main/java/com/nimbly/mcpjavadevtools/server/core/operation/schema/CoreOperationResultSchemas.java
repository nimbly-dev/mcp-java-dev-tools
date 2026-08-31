package com.nimbly.mcpjavadevtools.server.core.operation.schema;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Explicit result schemas for the heterogeneous Core operation families. */
public final class CoreOperationResultSchemas {

    private CoreOperationResultSchemas() {
    }

    public static OperationSchema artifact() {
        ObjectNode root = CanonicalOperationSchema.openObject();
        CoreOperationResultFields.string(root, "resultType");
        CoreOperationResultFields.string(root, "status");
        CoreOperationResultFields.string(root, "reasonCode");
        CoreOperationResultFields.nullableString(root, "nextActionCode");
        CoreOperationResultFields.nullableString(root, "nextAction");
        CoreOperationResultFields.nullableString(root, "reason");
        CoreOperationResultFields.openObject(root, "reasonMeta");
        CoreOperationResultFields.openObject(root, "details");
        CanonicalOperationSchema.required(root, "resultType", "status", "reasonCode", "reasonMeta", "details");
        return CanonicalOperationSchema.schema(root);
    }

    public static OperationSchema probe() {
        ObjectNode root = CanonicalOperationSchema.openObject();
        CoreOperationResultFields.string(root, "status");
        CoreOperationResultFields.string(root, "reasonCode");
        CoreOperationResultFields.openObject(root, "reasonMetadata");
        CoreOperationResultFields.nullableObject(root, "actionResult");
        CanonicalOperationSchema.required(root, "status", "reasonCode", "reasonMetadata", "actionResult");
        return CanonicalOperationSchema.schema(root);
    }

    public static OperationSchema jvmLifecycle() {
        ObjectNode root = CanonicalOperationSchema.openObject();
        CoreOperationResultFields.string(root, "status");
        CoreOperationResultFields.string(root, "reasonCode");
        CoreOperationResultFields.nullableObject(root, "actionResult");
        CanonicalOperationSchema.required(root, "status", "reasonCode", "actionResult");
        return CanonicalOperationSchema.schema(root);
    }

    public static OperationSchema export() {
        ObjectNode root = CanonicalOperationSchema.openObject();
        CoreOperationResultFields.string(root, "resultType");
        CoreOperationResultFields.string(root, "status");
        CoreOperationResultFields.string(root, "reasonCode");
        CoreOperationResultFields.nullableString(root, "nextActionCode");
        CoreOperationResultFields.nullableString(root, "nextAction");
        CoreOperationResultFields.nullableString(root, "reason");
        CoreOperationResultFields.openObject(root, "reasonMeta");
        CoreOperationResultFields.openObject(root, "details");
        CanonicalOperationSchema.required(root, "resultType", "status", "reasonCode", "reasonMeta", "details");
        return CanonicalOperationSchema.schema(root);
    }

    public static OperationSchema route() {
        ObjectNode root = CanonicalOperationSchema.openObject();
        CoreOperationResultFields.string(root, "resultType");
        CoreOperationResultFields.string(root, "status");
        CoreOperationResultFields.nullableString(root, "reasonCode");
        CoreOperationResultFields.nullableString(root, "failedStep");
        CoreOperationResultFields.nullableString(root, "nextActionCode");
        CoreOperationResultFields.nullableString(root, "nextAction");
        CoreOperationResultFields.nullableObject(root, "actionResult");
        CoreOperationResultFields.arrayOfStrings(root, "evidence");
        CoreOperationResultFields.arrayOfStrings(root, "attemptedStrategies");
        CanonicalOperationSchema.required(root, "resultType", "status", "evidence", "attemptedStrategies");
        return CanonicalOperationSchema.schema(root);
    }

    public static OperationSchema failure() {
        ObjectNode root = CanonicalOperationSchema.openObject();
        CoreOperationResultFields.string(root, "outcome");
        CoreOperationResultFields.string(root, "reasonCode");
        CoreOperationResultFields.string(root, "message");
        CoreOperationResultFields.nullableBoolean(root, "diagnosisClaimed");
        CoreOperationResultFields.nullableObject(root, "fingerprint");
        CoreOperationResultFields.nullableObject(root, "expectedFingerprint");
        CoreOperationResultFields.nullableObject(root, "observedFingerprint");
        CoreOperationResultFields.nullableObject(root, "lineHit");
        CoreOperationResultFields.nullableObject(root, "attemptEvidence");
        CoreOperationResultFields.nullableObject(root, "cleanupStatus");
        CoreOperationResultFields.nullableObject(root, "investigation");
        CoreOperationResultFields.arrayOfObjects(root, "investigationCandidates");
        CoreOperationResultFields.nullableObject(root, "dependencyBoundary");
        CoreOperationResultFields.arrayOfObjects(root, "exceptionSections");
        CoreOperationResultFields.arrayOfStrings(root, "incompleteReasons");
        CoreOperationResultFields.nullableInteger(root, "httpStatus");
        CanonicalOperationSchema.required(root, "outcome", "reasonCode", "message",
                "investigationCandidates", "exceptionSections", "incompleteReasons");
        return CanonicalOperationSchema.schema(root);
    }

    public static OperationSchema transport() {
        ObjectNode root = CanonicalOperationSchema.openObject();
        CoreOperationResultFields.string(root, "status");
        CoreOperationResultFields.nullableString(root, "reasonCode");
        CoreOperationResultFields.nullableString(root, "nextActionCode");
        CoreOperationResultFields.nullableString(root, "errorMessage");
        CoreOperationResultFields.openObject(root, "reasonMeta");
        CoreOperationResultFields.nullableString(root, "protocol");
        CoreOperationResultFields.nullableInteger(root, "statusCode");
        CoreOperationResultFields.openObject(root, "headers");
        CoreOperationResultFields.nullableString(root, "bodyPreview");
        CoreOperationResultFields.integer(root, "durationMs");
        CanonicalOperationSchema.required(root, "status", "reasonMeta", "headers", "durationMs");
        return CanonicalOperationSchema.schema(root);
    }

    public static OperationSchema suite() {
        ObjectNode root = CanonicalOperationSchema.openObject();
        CoreOperationResultFields.string(root, "status");
        CoreOperationResultFields.string(root, "reasonCode");
        CoreOperationResultFields.nullableString(root, "nextAction");
        CoreOperationResultFields.openObject(root, "reasonMeta");
        CoreOperationResultFields.openObject(root, "details");
        CanonicalOperationSchema.required(root, "status", "reasonCode", "reasonMeta", "details");
        return CanonicalOperationSchema.schema(root);
    }

}
