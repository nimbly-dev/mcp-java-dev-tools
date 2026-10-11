package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.ArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.RunResultOperations;
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
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.CoreOperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationGuarantee;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CoreOperationResultSchemas;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import java.util.function.Function;

/** Binds all 31 Artifact Management operations directly to their family owners. */
public class ArtifactOperationRegistrations {

    public static final String TOOL_NAME = "artifact_management";

    private static final EnumSet<ArtifactManagementAction> OWNED_ACTIONS = EnumSet.range(
            ArtifactManagementAction.PROBE_CONFIG_READ,
            ArtifactManagementAction.EXECUTION_EXPORT_GENERATE);

    private ArtifactOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            ProbeConfigOperations probe,
            ProjectContextOperations project,
            PlanOperations plans,
            RunResultOperations runs,
            ExecutionExportOperations exports,
            ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        return java.util.stream.Stream.of(
                        probeProjectOperations(probe, project, mapper),
                        planOperations(plans, mapper),
                        runOperations(runs, mapper),
                        exportOperations(exports, mapper))
                .flatMap(List::stream)
                .toList();
    }

    static List<OperationRegistration<?, ?>> probeProjectOperations(
            ProbeConfigOperations probe,
            ProjectContextOperations project,
            ObjectMapper mapper) {
        Objects.requireNonNull(probe, "probe operations must not be null");
        Objects.requireNonNull(project, "project operations must not be null");
        return List.of(
                register(ArtifactManagementAction.PROBE_CONFIG_READ, probe, "read", probe::read, mapper),
                register(ArtifactManagementAction.PROBE_CONFIG_VALIDATE, probe, "validate", probe::validate, mapper),
                register(ArtifactManagementAction.PROBE_CONFIG_UPSERT, probe, "upsert", probe::upsert, mapper),
                register(ArtifactManagementAction.PROBE_CONFIG_RELOAD, probe, "reload", probe::reload, mapper),
                register(ArtifactManagementAction.PROJECT_CONTEXT_READ, project, "read", project::read, mapper),
                register(ArtifactManagementAction.PROJECT_CONTEXT_VALIDATE, project, "validate", project::validate, mapper),
                register(ArtifactManagementAction.PROJECT_CONTEXT_UPSERT, project, "upsert", project::upsert, mapper),
                register(ArtifactManagementAction.PROJECT_CONTEXT_LIST, project, "list", project::list, mapper));
    }

    static List<OperationRegistration<?, ?>> planOperations(
            PlanOperations owner, ObjectMapper mapper) {
        Objects.requireNonNull(owner, "plan operations must not be null");
        return List.of(
                register(ArtifactManagementAction.PERFORMANCE_PLAN_READ, owner, "read(performance)",
                        request -> owner.read(request, "performance"), mapper),
                register(ArtifactManagementAction.PERFORMANCE_PLAN_VALIDATE, owner, "validate(performance)",
                        request -> owner.validate(request, "performance"), mapper),
                register(ArtifactManagementAction.PERFORMANCE_PLAN_UPSERT, owner, "upsert(performance)",
                        request -> owner.upsert(request, "performance"), mapper),
                register(ArtifactManagementAction.PERFORMANCE_PLAN_LIST, owner, "list(performance)",
                        request -> owner.list(request, "performance"), mapper),
                register(ArtifactManagementAction.REGRESSION_PLAN_READ, owner, "read(regression)",
                        request -> owner.read(request, "regression"), mapper),
                register(ArtifactManagementAction.REGRESSION_PLAN_VALIDATE, owner, "validate(regression)",
                        request -> owner.validate(request, "regression"), mapper),
                register(ArtifactManagementAction.REGRESSION_PLAN_UPSERT, owner, "upsert(regression)",
                        request -> owner.upsert(request, "regression"), mapper),
                register(ArtifactManagementAction.REGRESSION_PLAN_LIST, owner, "list(regression)",
                        request -> owner.list(request, "regression"), mapper),
                register(ArtifactManagementAction.SECURITY_PLAN_READ, owner, "read(security)",
                        request -> owner.read(request, "security"), mapper),
                register(ArtifactManagementAction.SECURITY_PLAN_VALIDATE, owner, "validate(security)",
                        request -> owner.validate(request, "security"), mapper),
                register(ArtifactManagementAction.SECURITY_PLAN_UPSERT, owner, "upsert(security)",
                        request -> owner.upsert(request, "security"), mapper),
                register(ArtifactManagementAction.SECURITY_PLAN_LIST, owner, "list(security)",
                        request -> owner.list(request, "security"), mapper));
    }

    static List<OperationRegistration<?, ?>> runOperations(
            RunResultOperations owner, ObjectMapper mapper) {
        Objects.requireNonNull(owner, "run operations must not be null");
        return List.of(
                register(ArtifactManagementAction.RUN_RESULT_READ, owner, "read", owner::read, mapper),
                register(ArtifactManagementAction.RUN_RESULT_UPSERT, owner, "upsert", owner::upsert, mapper),
                register(ArtifactManagementAction.RUN_RESULT_LIST, owner, "list", owner::list, mapper),
                register(ArtifactManagementAction.RUN_RESULT_REBUILD, owner, "rebuild", owner::rebuild, mapper),
                register(ArtifactManagementAction.RUN_RESULT_BACKFILL, owner, "backfill", owner::backfill, mapper),
                register(ArtifactManagementAction.RUN_RESULT_CUTOVER, owner, "cutover", owner::cutover, mapper),
                register(ArtifactManagementAction.RUN_RESULT_QUERY, owner, "query", owner::query, mapper),
                register(ArtifactManagementAction.RUN_RESULT_CLEANUP, owner, "cleanup", owner::cleanup, mapper));
    }

    static List<OperationRegistration<?, ?>> exportOperations(
            ExecutionExportOperations owner, ObjectMapper mapper) {
        Objects.requireNonNull(owner, "export operations must not be null");
        return List.of(
                register(ArtifactManagementAction.EXECUTION_EXPORT_READ, owner, "read", owner::read, mapper),
                register(ArtifactManagementAction.EXECUTION_EXPORT_LIST, owner, "list", owner::list, mapper),
                register(ArtifactManagementAction.EXECUTION_EXPORT_GENERATE, owner, "generate", owner::generate, mapper));
    }

    static OperationRegistration<ArtifactOperationArguments, ArtifactManagementResult> register(
            ArtifactManagementAction action,
            Object owner,
            String ownerMethod,
            Function<ArtifactManagementRequest, ArtifactManagementResult> operation,
            ObjectMapper mapper) {
        Object checkedOwner = Objects.requireNonNull(owner, "artifact operation owner must not be null");
        if (ownerMethod == null || ownerMethod.isBlank()) {
            throw new IllegalArgumentException("owner method must not be blank");
        }
        Function<ArtifactManagementRequest, ArtifactManagementResult> checkedOperation =
                Objects.requireNonNull(operation, "artifact operation must not be null");
        OperationDescriptor descriptor = descriptor(action, checkedOwner, ownerMethod);
        OperationExecutor<ArtifactOperationArguments, ArtifactManagementResult> executor =
                executor(action, checkedOperation);
        OperationRequestDecoder<ArtifactOperationArguments> decoder = OWNED_ACTIONS.contains(action)
                ? OperationRequestDecoders.wrap(mapper, ArtifactOperationArguments.class, ArtifactOperationArguments::new,
                        ArtifactOperationArguments::input)
                : ArtifactOperationArguments::new;
        OperationResultEncoder<ArtifactManagementResult> encoder = OWNED_ACTIONS.contains(action)
                ? OperationResultEncoders.typed(mapper, ArtifactManagementResult.class)
                : result -> mapper.valueToTree(ArtifactManagementResult.class.cast(result));
        return new OperationRegistration<>(
                descriptor, ArtifactOperationArguments.class, ArtifactManagementResult.class,
                new OperationRegistrationContract(ArtifactOperationSchemas.schema(action), CoreOperationResultSchemas.artifact(),
                        safety(action, descriptor)),
                decoder, executor, encoder);
    }

    static ArtifactManagementRequest decode(
            ArtifactManagementAction action, ArtifactOperationArguments arguments) {
        return new ArtifactManagementRequest(action.artifactType(), action.action(), arguments.input());
    }

    static OperationExecutor<ArtifactOperationArguments, ArtifactManagementResult> executor(
            ArtifactManagementAction action,
            Function<ArtifactManagementRequest, ArtifactManagementResult> operation) {
        if (OWNED_ACTIONS.contains(action)) {
            return ContextAwareOperationExecutor.declared(
                    OperationCancellationState.NOT_CANCELLABLE,
                    OperationCancellationGuarantee.DETERMINISTIC_CONTINUATION,
                    (input, context) -> operation.apply(decode(action, input)));
        }
        return input -> operation.apply(decode(action, input));
    }

    static OperationSafetyPolicy safety(
            ArtifactManagementAction action,
            OperationDescriptor descriptor) {
        OperationSafetyPolicy baseline = CoreOperationSafetyPolicy.forOperation(descriptor.operationId().value(),
                descriptor.trace().sideEffect());
        if (!OWNED_ACTIONS.contains(action)) {
            return baseline;
        }
        return new OperationSafetyPolicy(
                baseline.sideEffect(), baseline.confirmationRequired(), baseline.credentialPolicy(), baseline.redactionPolicy(),
                baseline.timeoutMillis(), false, baseline.maxInputBytes(),
                baseline.maxOutputBytes());
    }

    static OperationDescriptor descriptor(
            ArtifactManagementAction action,
            Object owner,
            String ownerMethod) {
        String id = OperationId.fromLegacy(TOOL_NAME, action.routeId()).value();
        String sideEffect = CoreOperationSafetyPolicy.sideEffect(id);
        String executableOwner = owner.getClass().getName() + "#" + ownerMethod;
        return new OperationDescriptor(
                TOOL_NAME,
                action.routeId(),
                ArtifactOperationArguments.class.getName(),
                ArtifactManagementResult.class.getName(),
                executableOwner,
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        ArtifactOperationRegistrations.class.getName(),
                        ArtifactManagementFeature.class.getName(),
                        ArtifactOperationRegistrations.class.getName(),
                        "mcpjvm-610:" + id + ":typed-binding",
                        sideEffect,
                        Map.of("executableOwner", executableOwner,
                                "operationId", id)));
    }

}
