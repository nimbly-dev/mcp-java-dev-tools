package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.PerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.TrustedSuiteExecution;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.action.PerformanceSuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.operation.PerformanceSuiteOperationArguments;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.request.PerformanceSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.result.PerformanceSuiteResult;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Binds the direct Performance Suite CDE operation to its substantive action owner. */
public class PerformanceSuiteOperationRegistrations {

    private static final String OPERATION_ID = "performance_suite.execute_plan";

    private PerformanceSuiteOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            PerformanceSuiteFeature feature, ObjectMapper mapper) {
        return createTrusted(feature, mapper, null);
    }

    /** Binds a workspace-backed direct CDE execution context. */
    static List<OperationRegistration<?, ?>> createTrusted(
            PerformanceSuiteFeature feature, ObjectMapper mapper, TrustedSuiteExecution trusted) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        PerformanceSuiteFeature owner = Objects.requireNonNull(
                feature, "performance suite feature must not be null");
        return List.of(register(owner, mapper, trusted));
    }

    static OperationRegistration<PerformanceSuiteOperationArguments, PerformanceSuiteResult> register(
            PerformanceSuiteFeature owner, ObjectMapper mapper, TrustedSuiteExecution trusted) {
        String executableOwner = owner.getClass().getName() + "#execute";
        OperationDescriptor descriptor = descriptor(executableOwner, owner);
        return new OperationRegistration<>(
                descriptor,
                PerformanceSuiteOperationArguments.class,
                PerformanceSuiteResult.class,
                new OperationRegistrationContract(
                        schema(), CoreOperationResultSchemas.suite(),
                        safetyPolicy()),
                OperationRequestDecoders.wrap(
                        mapper, PerformanceSuiteOperationArguments.class,
                        PerformanceSuiteOperationArguments::new,
                        PerformanceSuiteOperationArguments::input),
                ContextAwareOperationExecutor.declared(OperationCancellationState.NOT_CANCELLABLE,
                        OperationCancellationGuarantee.DETERMINISTIC_CONTINUATION,
                        (input, context) -> trusted == null
                                ? PerformanceSuiteResult.blocked("direct_suite_context_unavailable",
                                        "bind a trusted Artifact workspace before direct execution", Map.of())
                                : trusted.execute("performance", input.input(), PerformanceSuiteResult.class,
                                        resolved -> owner.execute(new PerformanceSuiteRequest(
                                                PerformanceSuiteAction.EXECUTE_PLAN, resolved)),
                                        code -> PerformanceSuiteResult.blocked(code,
                                                "inspect the persisted plan and workspace", Map.of()), true)),
                OperationResultEncoders.typed(mapper, PerformanceSuiteResult.class),
                CoreOperationDirectory.class.getName(),
                provenance());
    }

    static OperationDescriptor descriptor(String executableOwner, PerformanceSuiteFeature owner) {
        return new OperationDescriptor(
                "performance_suite", "execute_plan", PerformanceSuiteOperationArguments.class.getName(),
                PerformanceSuiteResult.class.getName(), executableOwner,
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        PerformanceSuiteOperationRegistrations.class.getName(),
                        PerformanceSuiteFeature.class.getName(),
                        PerformanceSuiteOperationRegistrations.class.getName(),
                        "mcpjvm-623:" + OPERATION_ID + ":direct-binding",
                        "filesystem_write",
                        Map.of("executableOwner", executableOwner,
                                "operationId", OPERATION_ID,
                                "bindingType", "canonical-json-plan",
                                "ownerType", owner.getClass().getName())));
    }

    static OperationSafetyPolicy safetyPolicy() {
        OperationSafetyPolicy base = CoreOperationSafetyPolicy.forOperation(OPERATION_ID, "filesystem_write");
        return new OperationSafetyPolicy(base.sideEffect(), base.confirmationRequired(),
                base.credentialPolicy(), base.redactionPolicy(), base.timeoutMillis(), false,
                base.maxInputBytes(), base.maxOutputBytes());
    }

    static OperationSchema schema() {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "projectName");
        CanonicalOperationSchema.string(root, "planName");
        CanonicalOperationSchema.string(root, "executionProfile");
        CanonicalOperationSchema.string(root, "suiteRunId");
        CanonicalOperationSchema.required(root, "projectName", "executionProfile", "planName", "suiteRunId");
        return CanonicalOperationSchema.schema(root);
    }

    static OperationProvenance provenance() {
        return OperationProvenance.direct(
                "execution_orchestration", "execute",
                Map.of("suiteType", "performance", "suiteAction", "execute_plan"),
                "execution_orchestration_performance_plan_to_direct_suite_request",
                "persisted_run_status_threshold_probe_and_report_semantics_preserved",
                "execution_orchestration_performance_execute_plan_direct_cde_parity");
    }

}
