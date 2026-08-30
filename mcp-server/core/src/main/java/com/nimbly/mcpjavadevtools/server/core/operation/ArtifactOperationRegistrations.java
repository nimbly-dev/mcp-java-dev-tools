package com.nimbly.mcpjavadevtools.server.core.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.ArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation.ArtifactOperationCatalog;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Binds all 31 Artifact Management owners to canonical operation inputs. */
public final class ArtifactOperationRegistrations {

    private ArtifactOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            ArtifactOperationCatalog catalog, ObjectMapper mapper) {
        Objects.requireNonNull(catalog, "artifact catalog must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        EnumMap<ArtifactManagementAction, Operation<ArtifactManagementAction,
                ArtifactManagementRequest, ArtifactManagementResult>> owners =
                new EnumMap<>(ArtifactManagementAction.class);
        for (Operation<ArtifactManagementAction, ArtifactManagementRequest, ArtifactManagementResult> owner
                : catalog.operations()) {
            if (owners.put(owner.operationId(), owner) != null) {
                throw new IllegalArgumentException("duplicate Artifact owner: " + owner.operationId());
            }
        }
        return java.util.Arrays.stream(ArtifactManagementAction.values())
                .<OperationRegistration<?, ?>>map(action ->
                        register(action, owners.get(action), mapper))
                .toList();
    }

    static OperationRegistration<ArtifactOperationArguments, ArtifactManagementResult> register(
            ArtifactManagementAction action,
            Operation<ArtifactManagementAction, ArtifactManagementRequest, ArtifactManagementResult> owner,
            ObjectMapper mapper) {
        Objects.requireNonNull(owner, "artifact operation owner must not be null");
        OperationDescriptor descriptor = descriptor(action, owner);
        return new OperationRegistration<>(
                descriptor,
                ArtifactOperationArguments.class,
                ArtifactManagementResult.class,
                new OperationRegistrationContract(
                        schema(action), CoreOperationResultSchemas.artifact(),
                        CoreOperationSafetyPolicy.forOperation(
                                descriptor.operationId().value(), descriptor.trace().sideEffect())),
                input -> new ArtifactOperationArguments(input),
                input -> owner.execute(decode(action, input)),
                result -> mapper.valueToTree(ArtifactManagementResult.class.cast(result)),
                CoreOperationDirectory.class.getName(),
                identity(action));
    }

    static ArtifactManagementRequest decode(
            ArtifactManagementAction action, ArtifactOperationArguments arguments) {
        return new ArtifactManagementRequest(action.artifactType(), action.action(), arguments.input());
    }

    static OperationSchema schema(ArtifactManagementAction action) {
        return switch (action) {
            case PROBE_CONFIG_READ, PROBE_CONFIG_VALIDATE, PROBE_CONFIG_UPSERT, PROBE_CONFIG_RELOAD ->
                    probeSchema();
            case PROJECT_CONTEXT_READ, PROJECT_CONTEXT_VALIDATE, PROJECT_CONTEXT_UPSERT,
                    PROJECT_CONTEXT_LIST -> projectSchema();
            case PERFORMANCE_PLAN_READ, PERFORMANCE_PLAN_VALIDATE, PERFORMANCE_PLAN_LIST,
                    REGRESSION_PLAN_READ, REGRESSION_PLAN_VALIDATE, REGRESSION_PLAN_LIST,
                    SECURITY_PLAN_READ, SECURITY_PLAN_VALIDATE, SECURITY_PLAN_LIST -> planReadSchema();
            case PERFORMANCE_PLAN_UPSERT, REGRESSION_PLAN_UPSERT, SECURITY_PLAN_UPSERT -> planUpsertSchema();
            case RUN_RESULT_READ, RUN_RESULT_UPSERT, RUN_RESULT_LIST, RUN_RESULT_REBUILD,
                    RUN_RESULT_BACKFILL, RUN_RESULT_CUTOVER, RUN_RESULT_QUERY, RUN_RESULT_CLEANUP ->
                    runSchema(action);
            case EXECUTION_EXPORT_READ, EXECUTION_EXPORT_LIST, EXECUTION_EXPORT_GENERATE ->
                    exportSchema(action);
        };
    }

    static OperationSchema probeSchema() {
        ObjectNode root = CanonicalOperationSchema.object();
        root.with("properties").putObject("payload")
                .put("type", "object").put("additionalProperties", true);
        return CanonicalOperationSchema.schema(root);
    }

    static OperationSchema projectSchema() {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "projectName");
        CanonicalOperationSchema.string(root, "projectRootAbs");
        root.with("properties").putObject("payload")
                .put("type", "object").put("additionalProperties", true);
        root.with("properties").putObject("replace").put("type", "boolean").put("default", false);
        root.with("properties").putObject("query")
                .put("type", "object").put("additionalProperties", true);
        return CanonicalOperationSchema.schema(root);
    }

    static OperationSchema planReadSchema() {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "projectName");
        CanonicalOperationSchema.string(root, "planName");
        root.with("properties").putObject("payload")
                .put("type", "object").put("additionalProperties", true);
        root.with("properties").putObject("query")
                .put("type", "object").put("additionalProperties", true);
        return CanonicalOperationSchema.schema(root);
    }

    static OperationSchema planUpsertSchema() {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "projectName");
        CanonicalOperationSchema.string(root, "planName");
        root.with("properties").putObject("payload")
                .put("type", "object").put("additionalProperties", true);
        return CanonicalOperationSchema.schema(root);
    }

    static OperationSchema runSchema(ArtifactManagementAction action) {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "projectName");
        CanonicalOperationSchema.enumString(root, "suiteType", "regression", "security");
        CanonicalOperationSchema.string(root, "planName");
        CanonicalOperationSchema.string(root, "runId");
        CanonicalOperationSchema.string(root, "projectRootAbs");
        CanonicalOperationSchema.string(root, "executionProfile");
        root.with("properties").putObject("strict").put("type", "boolean").put("default", false);
        CanonicalOperationSchema.enumString(root, "stateSurface",
                "run_state", "correlation_state", "watcher_state");
        root.with("properties").putObject("scope")
                .put("type", "object").put("additionalProperties", true);
        root.with("properties").putObject("retention")
                .put("type", "object").put("additionalProperties", true);
        root.with("properties").putObject("query")
                .put("type", "object").put("additionalProperties", true);
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

    static OperationSchema exportSchema(ArtifactManagementAction action) {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "projectName");
        CanonicalOperationSchema.enumString(root, "mode", "ps1", "sh", "postman");
        CanonicalOperationSchema.string(root, "planName");
        CanonicalOperationSchema.string(root, "executionProfile");
        CanonicalOperationSchema.string(root, "when");
        root.with("properties").putObject("includeResolvedSecrets")
                .put("type", "boolean").put("default", false);
        root.with("properties").putObject("includeRuntimeStartup")
                .put("type", "boolean").put("default", false);
        root.with("properties").putObject("includeHealthcheckGate")
                .put("type", "boolean").put("default", false);
        root.with("properties").putObject("contextBindings")
                .put("type", "object").put("additionalProperties", true);
        root.with("properties").putObject("contextValues")
                .put("type", "object").put("additionalProperties", true);
        root.with("properties").putObject("query")
                .put("type", "object").put("additionalProperties", true);
        CanonicalOperationSchema.enumString(root, "type", "ps1", "sh", "postman");
        return CanonicalOperationSchema.schema(root);
    }

    static OperationDescriptor descriptor(
            ArtifactManagementAction action,
            Operation<ArtifactManagementAction, ArtifactManagementRequest, ArtifactManagementResult> owner) {
        String id = OperationId.fromLegacy(ArtifactOperationCatalog.TOOL_NAME, action.routeId()).value();
        String sideEffect = CoreOperationSafetyPolicy.sideEffect(id);
        return new OperationDescriptor(
                ArtifactOperationCatalog.TOOL_NAME,
                action.routeId(),
                ArtifactOperationArguments.class.getName(),
                ArtifactManagementResult.class.getName(),
                owner.executableOwner(),
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        ArtifactOperationRegistrations.class.getName(),
                        ArtifactManagementFeature.class.getName(),
                        ArtifactOperationRegistrations.class.getName(),
                        "mcpjvm-610:" + id + ":typed-binding",
                        sideEffect,
                        Map.of("capabilityCatalog", ArtifactOperationCatalog.class.getName(),
                                "executableOwner", owner.executableOwner(),
                                "operationId", id)));
    }

    static OperationLegacyIdentity identity(ArtifactManagementAction action) {
        return new OperationLegacyIdentity(
                ArtifactOperationCatalog.TOOL_NAME,
                action.routeId(),
                false,
                Map.of("artifactType", action.artifactType().value(),
                        "action", action.action().value()),
                "artifact_type_and_action_to_canonical_operation_id",
                "artifact_result_envelope_fields_and_details_preserved",
                "artifact_management_" + action.artifactType().value() + "_" + action.action().value());
    }
}
