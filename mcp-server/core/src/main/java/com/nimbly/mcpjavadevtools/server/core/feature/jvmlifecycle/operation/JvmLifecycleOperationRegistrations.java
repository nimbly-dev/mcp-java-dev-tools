package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.JvmLifecycleFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleInput;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequestFactory;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CanonicalOperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CoreOperationResultSchemas;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationProvenance;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;

/** Binds all three JVM lifecycle owners to the typed request factory. */
public class JvmLifecycleOperationRegistrations {

    private JvmLifecycleOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            JvmLifecycleOperationCatalog catalog, ObjectMapper mapper) {
        Objects.requireNonNull(catalog, "JVM lifecycle catalog must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        JvmLifecycleRequestFactory factory = new JvmLifecycleRequestFactory();
        return catalog.operations().stream()
                .<OperationRegistration<?, ?>>map(owner ->
                        register(owner.operationId(), owner, factory, mapper))
                .toList();
    }

    static OperationRegistration<JvmLifecycleInput, JvmLifecycleResult> register(
            JvmLifecycleAction action,
            Operation<JvmLifecycleAction, JvmLifecycleRequest, JvmLifecycleResult> owner,
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
                OperationRequestDecoders.typed(mapper.copy()
                                .setSerializationInclusion(JsonInclude.Include.NON_NULL),
                        JvmLifecycleInput.class),
                ContextAwareOperationExecutor.declared(OperationCancellationState.BOUNDED_DELEGATE_CANCELLATION,
                        OperationCancellationGuarantee.DELEGATED_DEADLINE,
                        (input, context) -> owner.execute(factory.create(action, input))),
                OperationResultEncoders.typed(mapper, JvmLifecycleResult.class),
                CoreOperationDirectory.class.getName(),
                identity(action));
    }

    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    static OperationSchema schema(JvmLifecycleAction action) {
        ObjectNode root = CanonicalOperationSchema.object();
        if (action == JvmLifecycleAction.ATTACH || action == JvmLifecycleAction.DEACTIVATE) {
            CanonicalOperationSchema.string(root, "pid").put("minLength", 1)
                    .put("pattern", "[1-9][0-9]*");
            root.with("properties").putObject("expectedProcessStartEpochMs")
                    .put("type", "integer").put("minimum", 1);
            root.with("properties").putObject("confirm").put("type", "boolean")
                    .putArray("enum").add(true);
            CanonicalOperationSchema.required(root, "pid", "expectedProcessStartEpochMs", "confirm");
        }
        if (action == JvmLifecycleAction.ATTACH) {
            CanonicalOperationSchema.string(root, "probeHost").put("minLength", 1).put("maxLength", 255)
                    .put("default", "127.0.0.1");
            root.with("properties").putObject("probePort").put("type", "integer")
                    .put("minimum", 1).put("maximum", 65535).put("default", 9191);
            CanonicalOperationSchema.string(root, "include").put("minLength", 1).put("maxLength", 2048);
            CanonicalOperationSchema.string(root, "exclude").put("minLength", 1)
                    .put("maxLength", 2048);
        }
        return CanonicalOperationSchema.schema(root);
    }

    static OperationDescriptor descriptor(
            JvmLifecycleAction action,
            Operation<JvmLifecycleAction, JvmLifecycleRequest, JvmLifecycleResult> owner) {
        String id = OperationId.fromLegacy(JvmLifecycleOperationCatalog.TOOL_NAME, action.value()).value();
        String sideEffect = CoreOperationSafetyPolicy.sideEffect(id);
        return new OperationDescriptor(
                JvmLifecycleOperationCatalog.TOOL_NAME,
                action.value(),
                JvmLifecycleInput.class.getName(),
                JvmLifecycleResult.class.getName(),
                owner.executableOwner(),
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        JvmLifecycleOperationRegistrations.class.getName(),
                        JvmLifecycleFeature.class.getName(),
                        JvmLifecycleOperationRegistrations.class.getName(),
                        "mcpjvm-610:" + id + ":typed-binding",
                        sideEffect,
                        Map.of("capabilityCatalog", JvmLifecycleOperationCatalog.class.getName(),
                                "executableOwner", owner.executableOwner(),
                                "operationId", id)));
    }

    static OperationProvenance identity(JvmLifecycleAction action) {
        return OperationProvenance.released(
                JvmLifecycleOperationCatalog.TOOL_NAME,
                action.value(),
                Map.of(),
                "jvm_lifecycle_action_input_to_typed_request_factory",
                "jvm_lifecycle_status_reason_and_action_result_preserved",
                "jvm_lifecycle_" + action.value());
    }
}
