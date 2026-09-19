package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.DefaultTransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.action.TransportExecutionActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.TransportExecutionAction;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.operation.TransportExecuteArguments;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.ContextAwareOperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRequestDecoders;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationResultEncoders;
import com.nimbly.mcpjavadevtools.server.core.operation.composition.CoreOperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionContext;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.CoreOperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationGuarantee;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CanonicalOperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CoreOperationResultSchemas;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationLegacyIdentity;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;

/** Binds the actionless transport Tool to the Core transport boundary. */
public class TransportExecutionOperationRegistrations {

    private TransportExecutionOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            TransportExecutionFeature feature, ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        var defaultFeature = (DefaultTransportExecutionFeature) Objects.requireNonNull(
                feature, "transport feature must not be null");
        return List.<OperationRegistration<?, ?>>of(register(defaultFeature, mapper));
    }

    static OperationRegistration<TransportExecuteArguments, ExecuteTransportResult> register(
            DefaultTransportExecutionFeature feature, ObjectMapper mapper) {
        TransportExecutionActionHandler owner = feature.operationOwner(TransportExecutionAction.EXECUTE);
        OperationDescriptor descriptor = descriptor(owner);
        return new OperationRegistration<>(
                descriptor,
                TransportExecuteArguments.class,
                ExecuteTransportResult.class,
                new OperationRegistrationContract(
                        schema(), CoreOperationResultSchemas.transport(),
                        CoreOperationSafetyPolicy.forOperation(
                                descriptor.operationId().value(), descriptor.trace().sideEffect())),
                OperationRequestDecoders.typed(
                        mapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL),
                        TransportExecuteArguments.class),
                ContextAwareOperationExecutor.declared(OperationCancellationState.BOUNDED_DELEGATE_CANCELLATION,
                        OperationCancellationGuarantee.DELEGATED_DEADLINE,
                        (input, context) -> {
                            checkpoint(context);
                            ExecuteTransportResult result = owner.execute(decode(input));
                            checkpoint(context);
                            return result;
                        }),
                OperationResultEncoders.typed(mapper, ExecuteTransportResult.class),
                CoreOperationDirectory.class.getName(),
                identity());
    }

    static ExecuteTransportRequest decode(TransportExecuteArguments arguments) {
        TransportProtocol protocol = TransportProtocol.fromValue(arguments.protocol())
                .orElseThrow(() -> new IllegalArgumentException("unsupported transport protocol"));
        boolean wrappedOnly = arguments.options() == null
                || arguments.options().wrappedOnly() == null
                || arguments.options().wrappedOnly();
        return new ExecuteTransportRequest(protocol, arguments.request(), wrappedOnly);
    }

    static OperationSchema schema() {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.enumString(root, "protocol", "http", "grpc", "kafka", "custom");
        root.with("properties").putObject("request").put("type", "object")
                .put("additionalProperties", true);
        ObjectNode options = root.with("properties").putObject("options")
                .put("type", "object").put("additionalProperties", false);
        CanonicalOperationSchema.booleanValue(options, "wrappedOnly").put("default", true);
        options.putObject("default").put("wrappedOnly", true);
        CanonicalOperationSchema.required(root, "protocol", "request");
        return CanonicalOperationSchema.schema(root);
    }

    static OperationDescriptor descriptor(TransportExecutionActionHandler owner) {
        String id = OperationId.fromLegacy("transport_execute", "execute").value();
        String executableOwner = owner.getClass().getName() + "#execute";
        return new OperationDescriptor(
                "transport_execute", "execute", TransportExecuteArguments.class.getName(),
                ExecuteTransportResult.class.getName(), executableOwner,
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        TransportExecutionOperationRegistrations.class.getName(),
                        TransportExecutionActionHandler.class.getName(),
                        TransportExecutionOperationRegistrations.class.getName(),
                        "mcpjvm-610:" + id + ":typed-binding",
                        "transport_request",
                        Map.of("executableOwner", executableOwner,
                                "operationId", id,
                                "bindingType", "actionless")));
    }

    static OperationLegacyIdentity identity() {
        return new OperationLegacyIdentity(
                "transport_execute", "", true, Map.of(),
                "transport_protocol_request_options_to_typed_request",
                "transport_status_protocol_headers_body_and_duration_preserved",
                "transport_execute_public_request_contract");
    }

    private static void checkpoint(OperationExecutionContext context) {
        if (Thread.currentThread().isInterrupted() || context.cancellationRequested()) {
            throw new java.util.concurrent.CancellationException("transport execution was cancelled");
        }
    }
}
