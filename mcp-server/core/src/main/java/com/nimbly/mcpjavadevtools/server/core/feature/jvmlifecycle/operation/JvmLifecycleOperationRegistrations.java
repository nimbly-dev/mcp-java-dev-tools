package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.JvmLifecycleFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.JvmLifecycleActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleInput;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequestFactory;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.BoundedOperationRequestDecoder;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.ContextAwareOperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRequestDecoders;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationResultEncoders;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.CoreOperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationGuarantee;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CanonicalOperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CoreOperationResultSchemas;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;

/** Binds all three JVM lifecycle owners to the typed request factory. */
public class JvmLifecycleOperationRegistrations {

    public static final String TOOL_NAME = "jvm_lifecycle";

    private JvmLifecycleOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            List<? extends JvmLifecycleActionHandler> actionHandlers, ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        Map<JvmLifecycleAction, JvmLifecycleActionHandler> handlers = completeHandlers(actionHandlers);
        JvmLifecycleRequestFactory factory = new JvmLifecycleRequestFactory();
        return Arrays.stream(JvmLifecycleAction.values())
                .<OperationRegistration<?, ?>>map(action ->
                        register(action, handlers.get(action), factory, mapper))
                .toList();
    }

    private static Map<JvmLifecycleAction, JvmLifecycleActionHandler> completeHandlers(
            List<? extends JvmLifecycleActionHandler> actionHandlers) {
        Objects.requireNonNull(actionHandlers, "JVM lifecycle action handlers must not be null");
        Map<JvmLifecycleAction, JvmLifecycleActionHandler> handlers =
                new EnumMap<>(JvmLifecycleAction.class);
        for (JvmLifecycleActionHandler handler : actionHandlers) {
            JvmLifecycleActionHandler checked = Objects.requireNonNull(
                    handler, "JVM lifecycle action handler must not be null");
            JvmLifecycleAction action = Objects.requireNonNull(
                    checked.action(), "JVM lifecycle action must not be null");
            if (handlers.putIfAbsent(action, checked) != null) {
                throw new IllegalArgumentException(
                        "Duplicate JVM lifecycle action owner: " + action.value());
            }
        }
        if (handlers.size() != JvmLifecycleAction.values().length) {
            throw new IllegalArgumentException(
                    "JVM lifecycle action owners must cover every JVM lifecycle action");
        }
        return Map.copyOf(handlers);
    }

    static OperationRegistration<JvmLifecycleInput, JvmLifecycleResult> register(
            JvmLifecycleAction action,
            JvmLifecycleActionHandler owner,
            JvmLifecycleRequestFactory factory,
            ObjectMapper mapper) {
        Objects.requireNonNull(owner, "JVM lifecycle operation owner must not be null");
        OperationDescriptor descriptor = descriptor(action, owner);
        return new OperationRegistration<>(
                descriptor,
                JvmLifecycleInput.class,
                JvmLifecycleResult.class,
                new OperationRegistrationContract(
                        schema(action), CoreOperationResultSchemas.jvmLifecycle(),
                        CoreOperationSafetyPolicy.forOperation(
                                descriptor.operationId().value(), descriptor.trace().sideEffect())),
                cdeInputDecoder(action, mapper.copy()
                        .setSerializationInclusion(JsonInclude.Include.NON_NULL)),
                ContextAwareOperationExecutor.declared(OperationCancellationState.BOUNDED_DELEGATE_CANCELLATION,
                        OperationCancellationGuarantee.DELEGATED_DEADLINE,
                        (input, context) -> owner.execute(factory.create(action, input))),
                OperationResultEncoders.typed(mapper, JvmLifecycleResult.class));
    }

    private static BoundedOperationRequestDecoder<JvmLifecycleInput>
            cdeInputDecoder(JvmLifecycleAction action, ObjectMapper mapper) {
        return OperationRequestDecoders.wrap(mapper, JvmLifecycleInput.class, input -> {
            JvmLifecycleInput decoded = mapper.convertValue(input, JvmLifecycleInput.class);
            Boolean confirmed = action == JvmLifecycleAction.LIST_JVMS ? null : Boolean.TRUE;
            return new JvmLifecycleInput(
                    decoded.pid(), decoded.expectedProcessStartEpochMs(), confirmed,
                    decoded.probeHost(), decoded.probePort(), decoded.probeId(), decoded.include(), decoded.exclude());
        }, request -> mapper.valueToTree(new JvmLifecycleInput(
                request.pid(), request.expectedProcessStartEpochMs(), null,
                request.probeHost(), request.probePort(), request.probeId(), request.include(), request.exclude())));
    }

    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    static OperationSchema schema(JvmLifecycleAction action) {
        ObjectNode root = CanonicalOperationSchema.object();
        if (action == JvmLifecycleAction.ATTACH || action == JvmLifecycleAction.DEACTIVATE) {
            CanonicalOperationSchema.string(root, "pid").put("minLength", 1)
                    .put("pattern", "[1-9][0-9]*");
            root.with("properties").putObject("expectedProcessStartEpochMs")
                    .put("type", "integer").put("minimum", 1);
            CanonicalOperationSchema.required(root, "pid", "expectedProcessStartEpochMs");
        }
        if (action == JvmLifecycleAction.ATTACH) {
            CanonicalOperationSchema.string(root, "probeHost").put("minLength", 1).put("maxLength", 255)
                    .put("default", "127.0.0.1");
            root.with("properties").putObject("probePort").put("type", "integer")
                    .put("minimum", 1).put("maximum", 65535).put("default", 9191);
            CanonicalOperationSchema.string(root, "probeId").put("minLength", 1).put("maxLength", 2048);
            CanonicalOperationSchema.string(root, "include").put("minLength", 1).put("maxLength", 2048);
            CanonicalOperationSchema.string(root, "exclude").put("minLength", 1)
                    .put("maxLength", 2048);
        }
        return CanonicalOperationSchema.schema(root);
    }

    static OperationDescriptor descriptor(
            JvmLifecycleAction action,
            JvmLifecycleActionHandler owner) {
        String id = OperationId.fromLegacy(TOOL_NAME, action.value()).value();
        String sideEffect = CoreOperationSafetyPolicy.sideEffect(id);
        return new OperationDescriptor(
                TOOL_NAME,
                action.value(),
                JvmLifecycleInput.class.getName(),
                JvmLifecycleResult.class.getName(),
                owner.getClass().getName(),
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        JvmLifecycleOperationRegistrations.class.getName(),
                        JvmLifecycleFeature.class.getName(),
                        JvmLifecycleOperationRegistrations.class.getName(),
                        "mcpjvm-610:" + id + ":typed-binding",
                        sideEffect,
                        Map.of("executableOwner", owner.getClass().getName(),
                                "operationId", id)));
    }

}
