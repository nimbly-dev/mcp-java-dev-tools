package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
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
import com.nimbly.mcpjavadevtools.server.core.operation.composition.CoreOperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.CoreOperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationGuarantee;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CanonicalOperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CoreOperationResultSchemas;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationProvenance;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;

/** Binds the canonical export operation directly to the Artifact export owner. */
public class ExecutionProfileExportOperationRegistrations {

    private ExecutionProfileExportOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            ExecutionExportArtifactGateway gateway, ObjectMapper mapper) {
        Objects.requireNonNull(gateway, "export gateway must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        return List.<OperationRegistration<?, ?>>of(register(gateway, mapper));
    }

    static OperationRegistration<ExecutionProfileExportArguments, ExecutionProfileExportResult> register(
            ExecutionExportArtifactGateway gateway,
            ObjectMapper mapper) {
        OperationDescriptor descriptor = descriptor();
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
                        (input, context) -> ExecutionProfileExportResult.fromArtifactResult(
                                gateway.generate(new ArtifactManagementRequest(
                                        ArtifactType.EXECUTION_EXPORT, ArtifactAction.GENERATE,
                                        new ExecutionProfileExportArtifactInputMapper(mapper).map(input))))),
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

    static OperationDescriptor descriptor() {
        String id = "execution_profile_export.export";
        return new OperationDescriptor(
                "execution_profile_export",
                "export",
                ExecutionProfileExportArguments.class.getName(),
                ExecutionProfileExportResult.class.getName(),
                ExecutionExportOperations.class.getName(),
                new OperationTraceMetadata(
                        "operation_execute",
                        ExecutionProfileExportOperationRegistrations.class.getName(),
                        ExecutionExportOperations.class.getName(),
                        ExecutionProfileExportResult.class.getName(),
                        "mcpjvm-640:" + id + ":live-fixture-export",
                        "filesystem_export",
                        Map.of("artifactGateway", ExecutionExportArtifactGateway.class.getName(),
                                "executableOwner", ExecutionExportOperations.class.getName(),
                                "operationId", id)));
    }

    static OperationProvenance identity() {
        return OperationProvenance.releasedActionless(
                "execution_profile_export",
                Map.of(),
                "execution_profile_export_input_to_typed_artifact_request",
                "export_result_fields_preserved_without_artifact_discriminators",
                "execution_profile_export_input_contract");
    }
}
