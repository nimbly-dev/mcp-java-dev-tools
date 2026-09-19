package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.ExecutionProfileExportFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.action.ExecutionProfileExportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.result.ExecutionProfileExportResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.operation.ExecutionProfileExportArguments;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.ContextAwareOperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRequestDecoders;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationResultEncoders;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.Operation;
import com.nimbly.mcpjavadevtools.server.core.operation.composition.CoreOperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.CoreOperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationGuarantee;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CanonicalOperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CoreOperationResultSchemas;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationLegacyIdentity;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;

/** Binds the actionless released export Tool to its typed Core owner. */
public class ExecutionProfileExportOperationRegistrations {

    private ExecutionProfileExportOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            ExecutionProfileExportOperationCatalog catalog, ObjectMapper mapper) {
        Objects.requireNonNull(catalog, "export catalog must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        Operation<ExecutionProfileExportAction, ExecutionProfileExportRequest, ExecutionProfileExportResult> owner =
                catalog.operations().getFirst();
        return List.<OperationRegistration<?, ?>>of(register(owner, mapper));
    }

    static OperationRegistration<ExecutionProfileExportArguments, ExecutionProfileExportResult> register(
            Operation<ExecutionProfileExportAction, ExecutionProfileExportRequest, ExecutionProfileExportResult> owner,
            ObjectMapper mapper) {
        OperationDescriptor descriptor = descriptor(owner);
        return new OperationRegistration<>(
                descriptor,
                ExecutionProfileExportArguments.class,
                ExecutionProfileExportResult.class,
                new OperationRegistrationContract(
                        schema(), CoreOperationResultSchemas.export(),
                        safety(descriptor)),
                OperationRequestDecoders.typedWithDefaults(
                        mapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL),
                        ExecutionProfileExportArguments.class,
                        Map.of("includeResolvedSecrets", com.fasterxml.jackson.databind.node.BooleanNode.FALSE,
                                "contextBindings", mapper.createObjectNode(),
                                "contextValues", mapper.createObjectNode())),
                ContextAwareOperationExecutor.declared(OperationCancellationState.NOT_CANCELLABLE,
                        OperationCancellationGuarantee.DETERMINISTIC_CONTINUATION,
                        (input, context) -> owner.execute(decode(input))),
                OperationResultEncoders.typed(mapper, ExecutionProfileExportResult.class),
                CoreOperationDirectory.class.getName(),
                identity());
    }

    static OperationSafetyPolicy safety(OperationDescriptor descriptor) {
        OperationSafetyPolicy policy = CoreOperationSafetyPolicy.forOperation(
                descriptor.operationId().value(), descriptor.trace().sideEffect());
        return new OperationSafetyPolicy(
                policy.sideEffect(), policy.confirmationRequired(), policy.credentialPolicy(),
                policy.redactionPolicy(), policy.timeoutMillis(), false,
                policy.maxInputBytes(), policy.maxOutputBytes());
    }

    static ExecutionProfileExportRequest decode(ExecutionProfileExportArguments arguments) {
        return new ExecutionProfileExportRequest(
                ExecutionProfileExportAction.EXPORT,
                arguments.projectName(),
                arguments.exportId(),
                arguments.executionProfile(),
                arguments.planName(),
                arguments.when(),
                arguments.mode(),
                arguments.type(),
                defaultFalse(arguments.includeResolvedSecrets()),
                arguments.includeRuntimeStartup(),
                arguments.includeHealthcheckGate(),
                arguments.contextBindings(),
                arguments.contextValues());
    }

    private static boolean defaultFalse(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    static OperationSchema schema() {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "projectName");
        CanonicalOperationSchema.string(root, "exportId");
        CanonicalOperationSchema.string(root, "executionProfile");
        CanonicalOperationSchema.string(root, "planName");
        CanonicalOperationSchema.string(root, "when");
        CanonicalOperationSchema.enumString(root, "mode", "ps1", "sh", "postman");
        CanonicalOperationSchema.enumString(root, "type", "ps1", "sh", "postman");
        CanonicalOperationSchema.booleanValue(root, "includeResolvedSecrets").put("default", false);
        CanonicalOperationSchema.booleanValue(root, "includeRuntimeStartup");
        CanonicalOperationSchema.booleanValue(root, "includeHealthcheckGate");
        root.with("properties").putObject("contextBindings").put("type", "object")
                .putObject("additionalProperties").put("type", "string");
        root.with("properties").putObject("contextValues").put("type", "object")
                .putObject("additionalProperties").put("type", "string");
        return CanonicalOperationSchema.schema(root);
    }

    static OperationDescriptor descriptor(
            Operation<ExecutionProfileExportAction, ExecutionProfileExportRequest, ExecutionProfileExportResult> owner) {
        String id = OperationId.fromLegacy(
                ExecutionProfileExportOperationCatalog.TOOL_NAME,
                ExecutionProfileExportOperationCatalog.ACTION).value();
        return new OperationDescriptor(
                ExecutionProfileExportOperationCatalog.TOOL_NAME,
                ExecutionProfileExportOperationCatalog.ACTION,
                ExecutionProfileExportArguments.class.getName(),
                ExecutionProfileExportResult.class.getName(),
                owner.executableOwner(),
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        ExecutionProfileExportOperationRegistrations.class.getName(),
                        ExecutionProfileExportFeature.class.getName(),
                        ExecutionProfileExportOperationRegistrations.class.getName(),
                        "mcpjvm-610:" + id + ":typed-binding",
                        "filesystem_export",
                        Map.of("capabilityCatalog", ExecutionProfileExportOperationCatalog.class.getName(),
                                "executableOwner", owner.executableOwner(),
                                "operationId", id)));
    }

    static OperationLegacyIdentity identity() {
        return new OperationLegacyIdentity(
                ExecutionProfileExportOperationCatalog.TOOL_NAME,
                "",
                true,
                Map.of(),
                "execution_profile_export_input_to_typed_artifact_request",
                "export_result_fields_preserved_without_artifact_discriminators",
                "execution_profile_export_input_contract");
    }
}
