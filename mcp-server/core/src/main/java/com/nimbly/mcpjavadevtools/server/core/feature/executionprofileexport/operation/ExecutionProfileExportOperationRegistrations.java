package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation;

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
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.Operation;
import com.nimbly.mcpjavadevtools.server.core.operation.composition.CoreOperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.CoreOperationSafetyPolicy;
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
                        CoreOperationSafetyPolicy.forOperation(
                                descriptor.operationId().value(), descriptor.trace().sideEffect())),
                input -> mapper.convertValue(input, ExecutionProfileExportArguments.class),
                input -> owner.execute(decode((ExecutionProfileExportArguments) input)),
                result -> mapper.valueToTree(ExecutionProfileExportResult.class.cast(result)),
                CoreOperationDirectory.class.getName(),
                identity());
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
                arguments.includeResolvedSecrets(),
                arguments.includeRuntimeStartup(),
                arguments.includeHealthcheckGate(),
                arguments.contextBindings(),
                arguments.contextValues());
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
        CanonicalOperationSchema.booleanValue(root, "includeRuntimeStartup").put("default", false);
        CanonicalOperationSchema.booleanValue(root, "includeHealthcheckGate").put("default", false);
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
