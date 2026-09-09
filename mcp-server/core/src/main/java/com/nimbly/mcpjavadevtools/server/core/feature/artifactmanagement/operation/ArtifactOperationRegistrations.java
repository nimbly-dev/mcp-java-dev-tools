package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.ArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.operation.ArtifactOperationArguments;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.ContextAwareOperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRequestDecoder;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRequestDecoders;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationResultEncoder;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationResultEncoders;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.Operation;
import com.nimbly.mcpjavadevtools.server.core.operation.composition.CoreOperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.CoreOperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationGuarantee;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CoreOperationResultSchemas;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationLegacyIdentity;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;

/** Binds all 31 Artifact Management owners to canonical operation inputs. */
public class ArtifactOperationRegistrations {

    private static final EnumSet<ArtifactManagementAction> OWNED_ACTIONS = EnumSet.range(
            ArtifactManagementAction.PROBE_CONFIG_READ, ArtifactManagementAction.SECURITY_PLAN_LIST);

    private ArtifactOperationRegistrations() {
    }

    static List<ArtifactOperation> legacyOperations(
            ProbeConfigOperations probe,
            ProjectContextOperations project,
            PlanOperations plans,
            OperationTraceMetadata trace) {
        return java.util.stream.Stream.concat(
                probeProjectOperations(probe, project, trace).stream(),
                planOperations(plans, trace).stream()).toList();
    }

    static List<ArtifactOperation> probeProjectOperations(
            ProbeConfigOperations probe,
            ProjectContextOperations project,
            OperationTraceMetadata trace) {
        return List.of(
                new ArtifactOperation(ArtifactManagementAction.PROBE_CONFIG_READ, ProbeConfigOperations.class, probe, "read", probe::read, trace),
                new ArtifactOperation(ArtifactManagementAction.PROBE_CONFIG_VALIDATE, ProbeConfigOperations.class, probe, "validate", probe::validate, trace),
                new ArtifactOperation(ArtifactManagementAction.PROBE_CONFIG_UPSERT, ProbeConfigOperations.class, probe, "upsert", probe::upsert, trace),
                new ArtifactOperation(ArtifactManagementAction.PROBE_CONFIG_RELOAD, ProbeConfigOperations.class, probe, "reload", probe::reload, trace),
                new ArtifactOperation(ArtifactManagementAction.PROJECT_CONTEXT_READ, ProjectContextOperations.class, project, "read", project::read, trace),
                new ArtifactOperation(ArtifactManagementAction.PROJECT_CONTEXT_VALIDATE,
                        ProjectContextOperations.class, project, "validate", project::validate, trace),
                new ArtifactOperation(ArtifactManagementAction.PROJECT_CONTEXT_UPSERT,
                        ProjectContextOperations.class, project, "upsert", project::upsert, trace),
                new ArtifactOperation(ArtifactManagementAction.PROJECT_CONTEXT_LIST, ProjectContextOperations.class, project, "list", project::list, trace));
    }

    static List<ArtifactOperation> planOperations(
            PlanOperations owner, OperationTraceMetadata trace) {
        return List.of(
                new ArtifactOperation(ArtifactManagementAction.PERFORMANCE_PLAN_READ,
                        PlanOperations.class, owner, "read(performance)",
                        request -> owner.read(request, "performance"), trace),
                new ArtifactOperation(ArtifactManagementAction.PERFORMANCE_PLAN_VALIDATE,
                        PlanOperations.class, owner, "validate(performance)",
                        request -> owner.validate(request, "performance"), trace),
                new ArtifactOperation(ArtifactManagementAction.PERFORMANCE_PLAN_UPSERT,
                        PlanOperations.class, owner, "upsert(performance)",
                        request -> owner.upsert(request, "performance"), trace),
                new ArtifactOperation(ArtifactManagementAction.PERFORMANCE_PLAN_LIST,
                        PlanOperations.class, owner, "list(performance)",
                        request -> owner.list(request, "performance"), trace),
                new ArtifactOperation(ArtifactManagementAction.REGRESSION_PLAN_READ,
                        PlanOperations.class, owner, "read(regression)",
                        request -> owner.read(request, "regression"), trace),
                new ArtifactOperation(ArtifactManagementAction.REGRESSION_PLAN_VALIDATE,
                        PlanOperations.class, owner, "validate(regression)",
                        request -> owner.validate(request, "regression"), trace),
                new ArtifactOperation(ArtifactManagementAction.REGRESSION_PLAN_UPSERT,
                        PlanOperations.class, owner, "upsert(regression)",
                        request -> owner.upsert(request, "regression"), trace),
                new ArtifactOperation(ArtifactManagementAction.REGRESSION_PLAN_LIST,
                        PlanOperations.class, owner, "list(regression)",
                        request -> owner.list(request, "regression"), trace),
                new ArtifactOperation(ArtifactManagementAction.SECURITY_PLAN_READ,
                        PlanOperations.class, owner, "read(security)",
                        request -> owner.read(request, "security"), trace),
                new ArtifactOperation(ArtifactManagementAction.SECURITY_PLAN_VALIDATE,
                        PlanOperations.class, owner, "validate(security)",
                        request -> owner.validate(request, "security"), trace),
                new ArtifactOperation(ArtifactManagementAction.SECURITY_PLAN_UPSERT,
                        PlanOperations.class, owner, "upsert(security)",
                        request -> owner.upsert(request, "security"), trace),
                new ArtifactOperation(ArtifactManagementAction.SECURITY_PLAN_LIST,
                        PlanOperations.class, owner, "list(security)",
                        request -> owner.list(request, "security"), trace));
    }

    public static List<OperationRegistration<?, ?>> create(
            ArtifactOperationCatalog catalog, ObjectMapper mapper) {
        Objects.requireNonNull(catalog, "artifact catalog must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        return catalog.operations().stream()
                .<OperationRegistration<?, ?>>map(owner ->
                        register(owner.operationId(), owner, mapper))
                .toList();
    }

    static OperationRegistration<ArtifactOperationArguments, ArtifactManagementResult> register(
            ArtifactManagementAction action,
            Operation<ArtifactManagementAction, ArtifactManagementRequest, ArtifactManagementResult> owner,
            ObjectMapper mapper) {
        Objects.requireNonNull(owner, "artifact operation owner must not be null");
        OperationDescriptor descriptor = descriptor(action, owner);
        OperationExecutor<ArtifactOperationArguments, ArtifactManagementResult> executor = executor(action, owner);
        OperationRequestDecoder<ArtifactOperationArguments> decoder = isOwned(action)
                ? OperationRequestDecoders.wrap(mapper, ArtifactOperationArguments.class, ArtifactOperationArguments::new,
                        ArtifactOperationArguments::input)
                : ArtifactOperationArguments::new;
        OperationResultEncoder<ArtifactManagementResult> encoder = isOwned(action)
                ? OperationResultEncoders.typed(mapper, ArtifactManagementResult.class)
                : result -> mapper.valueToTree(ArtifactManagementResult.class.cast(result));
        return new OperationRegistration<>(
                descriptor, ArtifactOperationArguments.class, ArtifactManagementResult.class,
                new OperationRegistrationContract(ArtifactOperationSchemas.schema(action), CoreOperationResultSchemas.artifact(),
                        safety(action, descriptor)),
                decoder, executor, encoder, CoreOperationDirectory.class.getName(), identity(action));
    }

    static ArtifactManagementRequest decode(
            ArtifactManagementAction action, ArtifactOperationArguments arguments) {
        return new ArtifactManagementRequest(action.artifactType(), action.action(), arguments.input());
    }

    static OperationExecutor<ArtifactOperationArguments, ArtifactManagementResult> executor(
            ArtifactManagementAction action,
            Operation<ArtifactManagementAction, ArtifactManagementRequest, ArtifactManagementResult> owner) {
        if (isOwned(action)) {
            return ContextAwareOperationExecutor.declared(
                    OperationCancellationState.NOT_CANCELLABLE,
                    OperationCancellationGuarantee.DETERMINISTIC_CONTINUATION,
                    (input, context) -> owner.execute(decode(action, input)));
        }
        return input -> owner.execute(decode(action, input));
    }

    static OperationSafetyPolicy safety(
            ArtifactManagementAction action,
            OperationDescriptor descriptor) {
        OperationSafetyPolicy baseline = CoreOperationSafetyPolicy.forOperation(descriptor.operationId().value(),
                descriptor.trace().sideEffect());
        if (!isOwned(action)) {
            return baseline;
        }
        return new OperationSafetyPolicy(
                baseline.sideEffect(), baseline.confirmationRequired(), baseline.credentialPolicy(), baseline.redactionPolicy(),
                baseline.timeoutMillis(), false, baseline.maxInputBytes(),
                baseline.maxOutputBytes());
    }

    static boolean isOwned(ArtifactManagementAction action) {
        return OWNED_ACTIONS.contains(action);
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
        String scenario = "artifact_management_" + action.artifactType().value() + "_" + action.action().value();
        return new OperationLegacyIdentity(
                ArtifactOperationCatalog.TOOL_NAME,
                action.routeId(),
                false,
                Map.of("artifactType", action.artifactType().value(),
                        "action", action.action().value()),
                "unwrap_legacy_input_object_and_bind_to_" + scenario + "_typed_arguments",
                "compare_status_reasonCode_message_nextActionCode_details_and_persisted_artifacts_for_"
                        + scenario,
                scenario);
    }
}
