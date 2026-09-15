package com.nimbly.mcpjavadevtools.server.core.feature.probe.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequestFactory;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequestInput;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.operation.ProbeOperationArguments;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.ContextAwareOperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRequestDecoders;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationResultEncoders;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.Operation;
import com.nimbly.mcpjavadevtools.server.core.operation.composition.CoreOperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.CoreOperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationGuarantee;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CoreOperationResultSchemas;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationLegacyIdentity;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;

/** Binds all seven Probe owners to the typed request factory. */
public class ProbeOperationRegistrations {

    private ProbeOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            ProbeOperationCatalog catalog, ObjectMapper mapper) {
        Objects.requireNonNull(catalog, "probe catalog must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        ProbeRequestFactory factory = new ProbeRequestFactory();
        return catalog.operations().stream()
                .<OperationRegistration<?, ?>>map(owner ->
                        register(owner.operationId(), owner, factory, mapper))
                .toList();
    }

    static OperationRegistration<ProbeOperationArguments, ProbeResult> register(
            ProbeAction action,
            Operation<ProbeAction, ProbeRequest, ProbeResult> owner,
            ProbeRequestFactory factory,
            ObjectMapper mapper) {
        Objects.requireNonNull(owner, "probe operation owner must not be null");
        OperationDescriptor descriptor = descriptor(action, owner);
        return new OperationRegistration<>(
                descriptor,
                ProbeOperationArguments.class,
                ProbeResult.class,
                new OperationRegistrationContract(
                        ProbeOperationSchemas.schema(action), CoreOperationResultSchemas.probe(),
                        CoreOperationSafetyPolicy.forOperation(
                                descriptor.operationId().value(), descriptor.trace().sideEffect())),
                OperationRequestDecoders.typed(mapper.copy()
                                .setSerializationInclusion(JsonInclude.Include.NON_NULL),
                        ProbeOperationArguments.class),
                ContextAwareOperationExecutor.declared(OperationCancellationState.BOUNDED_DELEGATE_CANCELLATION,
                        OperationCancellationGuarantee.DELEGATED_DEADLINE,
                        (input, context) -> owner.execute(factory.create(action, decode(input)))),
                OperationResultEncoders.typed(mapper, ProbeResult.class),
                CoreOperationDirectory.class.getName(),
                identity(action));
    }

    static ProbeRequestInput decode(ProbeOperationArguments arguments) {
        return new ProbeRequestInput(
                arguments.baseUrl(),
                arguments.probeId(),
                arguments.http() == null || arguments.http().headers() == null
                        ? Map.of() : arguments.http().headers(),
                arguments.timeoutMs(),
                arguments.key(),
                arguments.keys(),
                arguments.lineHint(),
                arguments.className(),
                arguments.pollIntervalMs(),
                arguments.maxRetries(),
                arguments.captureId(),
                arguments.action(),
                arguments.sessionId(),
                arguments.actuatorId(),
                arguments.targetKey(),
                arguments.returnBoolean(),
                arguments.ttlMs(),
                arguments.provider(),
                arguments.event(),
                arguments.intervalNanos(),
                arguments.outputPath(),
                arguments.outputFormat());
    }

    static OperationDescriptor descriptor(
            ProbeAction action, Operation<ProbeAction, ProbeRequest, ProbeResult> owner) {
        String id = OperationId.fromLegacy(ProbeOperationCatalog.TOOL_NAME, action.value()).value();
        String sideEffect = CoreOperationSafetyPolicy.sideEffect(id);
        return new OperationDescriptor(
                ProbeOperationCatalog.TOOL_NAME,
                action.value(),
                ProbeOperationArguments.class.getName(),
                ProbeResult.class.getName(),
                owner.executableOwner(),
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        ProbeOperationRegistrations.class.getName(),
                        ProbeFeature.class.getName(),
                        ProbeOperationRegistrations.class.getName(),
                        "mcpjvm-610:" + id + ":typed-binding",
                        sideEffect,
                        Map.of("capabilityCatalog", ProbeOperationCatalog.class.getName(),
                                "executableOwner", owner.executableOwner(),
                                "operationId", id)));
    }

    static OperationLegacyIdentity identity(ProbeAction action) {
        return new OperationLegacyIdentity(
                ProbeOperationCatalog.TOOL_NAME,
                action.value(),
                false,
                Map.of(),
                "probe_action_input_to_typed_request_factory",
                "probe_result_status_reason_and_action_result_preserved",
                "probe_" + action.value());
    }
}
