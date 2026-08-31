package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.operation.TransportExecuteArguments;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.composition.CoreOperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.CoreOperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CanonicalOperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CoreOperationResultSchemas;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationLegacyIdentity;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;

/** Binds the actionless transport Tool to the Core transport boundary. */
public final class TransportExecutionOperationRegistrations {

    private TransportExecutionOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            TransportExecutionFeature feature, ObjectMapper mapper) {
        Objects.requireNonNull(feature, "transport feature must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        return List.<OperationRegistration<?, ?>>of(register(feature, mapper));
    }

    static OperationRegistration<TransportExecuteArguments, ExecuteTransportResult> register(
            TransportExecutionFeature feature, ObjectMapper mapper) {
        OperationDescriptor descriptor = descriptor(feature);
        return new OperationRegistration<>(
                descriptor,
                TransportExecuteArguments.class,
                ExecuteTransportResult.class,
                new OperationRegistrationContract(
                        schema(), CoreOperationResultSchemas.transport(),
                        CoreOperationSafetyPolicy.forOperation(
                                descriptor.operationId().value(), descriptor.trace().sideEffect())),
                input -> mapper.convertValue(input, TransportExecuteArguments.class),
                input -> feature.execute(decode((TransportExecuteArguments) input)),
                result -> mapper.valueToTree(ExecuteTransportResult.class.cast(result)),
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

    static OperationDescriptor descriptor(TransportExecutionFeature feature) {
        String id = OperationId.fromLegacy("transport_execute", "execute").value();
        return new OperationDescriptor(
                "transport_execute", "execute", TransportExecuteArguments.class.getName(),
                ExecuteTransportResult.class.getName(), feature.getClass().getName() + "#execute",
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        TransportExecutionOperationRegistrations.class.getName(),
                        TransportExecutionFeature.class.getName(),
                        TransportExecutionOperationRegistrations.class.getName(),
                        "mcpjvm-610:" + id + ":typed-binding",
                        "transport_request",
                        Map.of("featureOwner", feature.getClass().getName(),
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
}
