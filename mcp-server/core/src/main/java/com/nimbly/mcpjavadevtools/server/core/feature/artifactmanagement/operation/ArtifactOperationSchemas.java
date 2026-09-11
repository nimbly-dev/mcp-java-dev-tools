package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CanonicalOperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;

/** Canonical input schemas for Artifact operation-directory registrations. */
final class ArtifactOperationSchemas {

    private ArtifactOperationSchemas() {
    }

    static OperationSchema schema(ArtifactManagementAction action) {
        return switch (action) {
            case PROBE_CONFIG_READ, PROBE_CONFIG_VALIDATE, PROBE_CONFIG_UPSERT,
                    PROBE_CONFIG_RELOAD -> probe(action);
            case PROJECT_CONTEXT_READ, PROJECT_CONTEXT_VALIDATE, PROJECT_CONTEXT_UPSERT,
                    PROJECT_CONTEXT_LIST -> project(action);
            case PERFORMANCE_PLAN_READ, PERFORMANCE_PLAN_VALIDATE, PERFORMANCE_PLAN_LIST,
                    REGRESSION_PLAN_READ, REGRESSION_PLAN_VALIDATE, REGRESSION_PLAN_LIST,
                    SECURITY_PLAN_READ, SECURITY_PLAN_VALIDATE, SECURITY_PLAN_LIST -> planRead();
            case PERFORMANCE_PLAN_UPSERT, REGRESSION_PLAN_UPSERT, SECURITY_PLAN_UPSERT -> planUpsert();
            case RUN_RESULT_READ, RUN_RESULT_UPSERT, RUN_RESULT_LIST, RUN_RESULT_REBUILD,
                    RUN_RESULT_BACKFILL, RUN_RESULT_CUTOVER, RUN_RESULT_QUERY, RUN_RESULT_CLEANUP -> run(action);
            case EXECUTION_EXPORT_READ, EXECUTION_EXPORT_LIST, EXECUTION_EXPORT_GENERATE -> export();
        };
    }

    static OperationSchema probe(ArtifactManagementAction action) {
        ObjectNode root = CanonicalOperationSchema.object();
        if (action == ArtifactManagementAction.PROBE_CONFIG_UPSERT) {
            root.with("properties").putObject("payload")
                    .put("type", "object").put("additionalProperties", true);
            CanonicalOperationSchema.required(root, "payload");
        }
        return CanonicalOperationSchema.schema(root);
    }

    static OperationSchema project(ArtifactManagementAction action) {
        ObjectNode root = CanonicalOperationSchema.object();
        if (action == ArtifactManagementAction.PROJECT_CONTEXT_LIST) {
            return CanonicalOperationSchema.schema(root);
        }
        boundedString(root, "projectName", 128);
        if (action == ArtifactManagementAction.PROJECT_CONTEXT_READ
                || action == ArtifactManagementAction.PROJECT_CONTEXT_VALIDATE) {
            boundedString(root, "projectRootAbs", 4096);
        }
        if (action == ArtifactManagementAction.PROJECT_CONTEXT_READ) {
            addQuery(root);
        }
        if (action == ArtifactManagementAction.PROJECT_CONTEXT_UPSERT) {
            root.with("properties").putObject("payload")
                    .put("type", "object").put("additionalProperties", true);
            root.with("properties").putObject("replace").put("type", "boolean").put("default", false);
            CanonicalOperationSchema.required(root, "projectName", "payload");
        }
        return CanonicalOperationSchema.schema(root);
    }

    static OperationSchema planRead() {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "projectName");
        CanonicalOperationSchema.string(root, "planName");
        root.with("properties").putObject("payload")
                .put("type", "object").put("additionalProperties", true);
        root.with("properties").putObject("query")
                .put("type", "object").put("additionalProperties", true);
        return CanonicalOperationSchema.schema(root);
    }

    static OperationSchema planUpsert() {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "projectName");
        CanonicalOperationSchema.string(root, "planName");
        root.with("properties").putObject("payload")
                .put("type", "object").put("additionalProperties", true);
        return CanonicalOperationSchema.schema(root);
    }

    static OperationSchema run(ArtifactManagementAction action) {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "projectName");
        CanonicalOperationSchema.enumString(root, "suiteType", "regression", "performance", "security");
        CanonicalOperationSchema.string(root, "planName");
        CanonicalOperationSchema.string(root, "runId");
        CanonicalOperationSchema.string(root, "projectRootAbs");
        CanonicalOperationSchema.string(root, "executionProfile");
        root.with("properties").putObject("strict").put("type", "boolean").put("default", false);
        CanonicalOperationSchema.enumString(root, "stateSurface",
                "run_state", "correlation_state", "watcher_state", "external_verification_state");
        for (String field : java.util.List.of("scope", "retention", "query")) {
            root.with("properties").putObject(field)
                    .put("type", "object").put("additionalProperties", true);
        }
        if (action == ArtifactManagementAction.RUN_RESULT_UPSERT) {
            root.with("properties").putObject("payload")
                    .put("type", "object").put("additionalProperties", true);
        }
        if (action == ArtifactManagementAction.RUN_RESULT_BACKFILL) {
            CanonicalOperationSchema.required(root, "stateSurface");
            root.with("properties").with("stateSurface").putArray("enum").add("correlation_state");
        }
        return CanonicalOperationSchema.schema(root);
    }

    static OperationSchema export() {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "projectName");
        CanonicalOperationSchema.enumString(root, "mode", "ps1", "sh", "postman");
        CanonicalOperationSchema.string(root, "planName");
        CanonicalOperationSchema.string(root, "executionProfile");
        CanonicalOperationSchema.string(root, "when");
        for (String field : java.util.List.of(
                "includeResolvedSecrets", "includeRuntimeStartup", "includeHealthcheckGate")) {
            root.with("properties").putObject(field).put("type", "boolean").put("default", false);
        }
        for (String field : java.util.List.of("contextBindings", "contextValues", "query")) {
            root.with("properties").putObject(field)
                    .put("type", "object").put("additionalProperties", true);
        }
        CanonicalOperationSchema.enumString(root, "type", "ps1", "sh", "postman");
        return CanonicalOperationSchema.schema(root);
    }

    static void boundedString(ObjectNode root, String name, int maxLength) {
        CanonicalOperationSchema.string(root, name).put("minLength", 1).put("maxLength", maxLength);
    }

    static void addQuery(ObjectNode root) {
        ObjectNode query = root.with("properties").putObject("query")
                .put("type", "object").put("additionalProperties", false);
        ObjectNode properties = query.putObject("properties");
        ObjectNode select = properties.putObject("select").put("type", "array").put("maxItems", 7);
        select.putObject("items").put("type", "string").putArray("enum")
                .add("artifact").add("summary").add("workspaces").add("executionProfiles")
                .add("runtimeContexts").add("scripts").add("runPrerequisites");
        properties.putObject("executionProfile").put("type", "string").put("maxLength", 128);
    }
}
