package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.DefaultExecutionOrchestrationFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.ExecutionOrchestrationFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.action.ExecutionOrchestrationActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.action.ExecutionOrchestrationAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.request.ExecutionOrchestrationRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.result.ExecutionOrchestrationResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.operation.ExecutionOrchestrationArguments;
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

/** Binds execution orchestration to its explicit persisted-state request contract. */
public class ExecutionOrchestrationOperationRegistrations {

    private ExecutionOrchestrationOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            ExecutionOrchestrationFeature feature, ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        var defaultFeature = (DefaultExecutionOrchestrationFeature) Objects.requireNonNull(
                feature, "execution orchestration feature must not be null");
        return List.<OperationRegistration<?, ?>>of(register(defaultFeature, mapper));
    }

    static OperationRegistration<ExecutionOrchestrationArguments, ExecutionOrchestrationResult> register(
            DefaultExecutionOrchestrationFeature feature, ObjectMapper mapper) {
        ExecutionOrchestrationActionHandler owner = feature.operationOwner(ExecutionOrchestrationAction.EXECUTE);
        String id = "execution_orchestration.execute";
        String executableOwner = owner.getClass().getName() + "#execute";
        OperationDescriptor descriptor = new OperationDescriptor(
                "execution_orchestration", "execute", ExecutionOrchestrationArguments.class.getName(),
                ExecutionOrchestrationResult.class.getName(), executableOwner,
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        ExecutionOrchestrationOperationRegistrations.class.getName(),
                        ExecutionOrchestrationActionHandler.class.getName(),
                        ExecutionOrchestrationOperationRegistrations.class.getName(),
                        "mcpjvm-610:" + id + ":typed-binding",
                        "filesystem_write",
                        Map.of("executableOwner", executableOwner,
                                "operationId", id,
                                "bindingType", "canonical-payload")));
        return new OperationRegistration<>(
                descriptor,
                ExecutionOrchestrationArguments.class,
                ExecutionOrchestrationResult.class,
                new OperationRegistrationContract(
                        schema(), CoreOperationResultSchemas.suite(),
                        CoreOperationSafetyPolicy.forOperation(id, "filesystem_write")),
                OperationRequestDecoders.typed(
                        mapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL),
                        ExecutionOrchestrationArguments.class),
                ContextAwareOperationExecutor.declared(OperationCancellationState.CONTEXT_AWARE_CANCELLATION,
                        OperationCancellationGuarantee.DETERMINISTIC_CONTINUATION,
                        (input, context) -> {
                            checkpoint(context);
                            ExecutionOrchestrationResult result = owner.execute(decode(input, mapper));
                            checkpoint(context);
                            return result;
                        }),
                OperationResultEncoders.typed(mapper, ExecutionOrchestrationResult.class),
                CoreOperationDirectory.class.getName(),
                new OperationLegacyIdentity(
                        "execution_orchestration", "execute", false, Map.of(),
                        "execution_orchestration_payload_to_typed_core_request",
                        "execution_orchestration_status_reason_next_action_and_details_preserved",
                        "execution_orchestration_execute_public_request_contract"));
    }

    static ExecutionOrchestrationRequest decode(
            ExecutionOrchestrationArguments arguments, ObjectMapper mapper) {
        ObjectNode input = mapper.createObjectNode();
        input.put("projectName", arguments.projectName());
        input.put("executionProfile", arguments.executionProfile());
        if (arguments.suiteRunId() != null) {
            input.put("suiteRunId", arguments.suiteRunId());
        }
        if (arguments.maxPlansPerCall() != null) {
            input.put("maxPlansPerCall", arguments.maxPlansPerCall());
        }
        return new ExecutionOrchestrationRequest(ExecutionOrchestrationAction.EXECUTE, input);
    }

    static OperationSchema schema() {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "projectName");
        CanonicalOperationSchema.string(root, "executionProfile");
        CanonicalOperationSchema.string(root, "suiteRunId");
        root.with("properties").putObject("maxPlansPerCall").put("type", "integer").put("minimum", 1);
        CanonicalOperationSchema.required(root, "projectName", "executionProfile");
        return CanonicalOperationSchema.schema(root);
    }

    private static void checkpoint(OperationExecutionContext context) {
        if (Thread.currentThread().isInterrupted() || context.cancellationRequested()) {
            throw new java.util.concurrent.CancellationException("execution orchestration was cancelled");
        }
    }
}
