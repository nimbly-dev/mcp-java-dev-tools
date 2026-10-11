package com.nimbly.mcpjavadevtools.server.core.feature.probe.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
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
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.CoreOperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationGuarantee;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CoreOperationResultSchemas;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import java.util.EnumMap;

/** Binds all seven Probe owners to the typed request factory. */
public class ProbeOperationRegistrations {

    public static final String TOOL_NAME = "probe";

    private ProbeOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            List<? extends ProbeActionHandler> actionHandlers, ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        Map<ProbeAction, ProbeActionHandler> handlers = completeHandlers(actionHandlers);
        ProbeRequestFactory factory = new ProbeRequestFactory();
        return java.util.Arrays.stream(ProbeAction.values())
                .<OperationRegistration<?, ?>>map(action ->
                        register(action, handlers.get(action), factory, mapper))
                .toList();
    }

    private static Map<ProbeAction, ProbeActionHandler> completeHandlers(
            List<? extends ProbeActionHandler> actionHandlers) {
        Objects.requireNonNull(actionHandlers, "probe action handlers must not be null");
        Map<ProbeAction, ProbeActionHandler> handlers = new EnumMap<>(ProbeAction.class);
        for (ProbeActionHandler handler : actionHandlers) {
            ProbeActionHandler checked = Objects.requireNonNull(handler, "probe action handler must not be null");
            ProbeAction action = Objects.requireNonNull(checked.action(), "probe action must not be null");
            if (handlers.putIfAbsent(action, checked) != null) {
                throw new IllegalArgumentException("Duplicate Probe action owner: " + action.value());
            }
        }
        if (handlers.size() != ProbeAction.values().length) {
            throw new IllegalArgumentException("Probe action owners must cover every Probe action");
        }
        return Map.copyOf(handlers);
    }

    static OperationRegistration<ProbeOperationArguments, ProbeResult> register(
            ProbeAction action,
            ProbeActionHandler owner,
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
                OperationResultEncoders.typed(mapper, ProbeResult.class));
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
            ProbeAction action, ProbeActionHandler owner) {
        String id = OperationId.fromLegacy(TOOL_NAME, action.value()).value();
        String sideEffect = CoreOperationSafetyPolicy.sideEffect(id);
        return new OperationDescriptor(
                TOOL_NAME,
                action.value(),
                ProbeOperationArguments.class.getName(),
                ProbeResult.class.getName(),
                owner.getClass().getName(),
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        ProbeOperationRegistrations.class.getName(),
                        ProbeFeature.class.getName(),
                        ProbeOperationRegistrations.class.getName(),
                        "mcpjvm-610:" + id + ":typed-binding",
                        sideEffect,
                        Map.of("executableOwner", owner.getClass().getName(),
                                "operationId", id)));
    }

}
