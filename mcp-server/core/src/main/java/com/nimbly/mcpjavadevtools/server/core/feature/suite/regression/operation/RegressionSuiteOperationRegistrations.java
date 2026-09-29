package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.operation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.RegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.TrustedSuiteExecution;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.operation.RegressionSuiteOperationArguments;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result.RegressionSuiteResult;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
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
import java.util.function.Function;

/** Binds both direct Regression Suite CDE operations to the plan owner. */
public class RegressionSuiteOperationRegistrations {

    private RegressionSuiteOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            RegressionSuiteFeature feature, ObjectMapper mapper) {
        return createTrusted(feature, mapper, null);
    }

    /** Binds workspace-backed direct CDE operations. */
    static List<OperationRegistration<?, ?>> createTrusted(
            RegressionSuiteFeature feature, ObjectMapper mapper, TrustedSuiteExecution trusted) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        RegressionSuiteFeature owner = Objects.requireNonNull(
                feature, "regression suite feature must not be null");
        return List.of(
                register("preflight", owner, owner::preflight, mapper, trusted, false),
                register("execute_plan", owner, owner::executePlan, mapper, trusted, true));
    }

    static OperationRegistration<RegressionSuiteOperationArguments, RegressionSuiteResult> register(
            String actionName, RegressionSuiteFeature owner,
            Function<JsonNode, RegressionSuiteResult> execute, ObjectMapper mapper,
            TrustedSuiteExecution trusted, boolean persist) {
        String id = OperationId.of("regression_suite." + actionName).value();
        String executableOwner = owner.getClass().getName() + "#"
                + (persist ? "executePlan" : "preflight");
        OperationDescriptor descriptor = descriptor(id, actionName, executableOwner, owner);
        return new OperationRegistration<>(
                descriptor,
                RegressionSuiteOperationArguments.class,
                RegressionSuiteResult.class,
                new OperationRegistrationContract(
                        schema(), CoreOperationResultSchemas.suite(),
                        safetyPolicy(id, descriptor.trace().sideEffect())),
                OperationRequestDecoders.wrap(
                        mapper, RegressionSuiteOperationArguments.class,
                        RegressionSuiteOperationArguments::new,
                        RegressionSuiteOperationArguments::input),
                ContextAwareOperationExecutor.declared(OperationCancellationState.NOT_CANCELLABLE,
                        OperationCancellationGuarantee.DETERMINISTIC_CONTINUATION,
                        (input, context) -> trusted == null
                                ? RegressionSuiteResult.blocked("direct_suite_context_unavailable",
                                        "bind a trusted Artifact workspace before direct execution", Map.of())
                                : trusted.execute("regression", input.input(), RegressionSuiteResult.class,
                                        execute,
                                        code -> RegressionSuiteResult.blocked(code,
                                                "inspect the persisted plan and workspace", Map.of()),
                                        persist)),
                OperationResultEncoders.typed(mapper, RegressionSuiteResult.class),
                CoreOperationDirectory.class.getName(),
                provenance(actionName));
    }

    static OperationDescriptor descriptor(
            String id, String actionName, String executableOwner, RegressionSuiteFeature owner) {
        return new OperationDescriptor(
                "regression_suite", actionName, RegressionSuiteOperationArguments.class.getName(),
                RegressionSuiteResult.class.getName(), executableOwner,
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        RegressionSuiteOperationRegistrations.class.getName(),
                        RegressionSuiteFeature.class.getName(),
                        RegressionSuiteOperationRegistrations.class.getName(),
                        "mcpjvm-623:" + id + ":direct-binding",
                        CoreOperationSafetyPolicy.sideEffect(id),
                        Map.of("executableOwner", executableOwner,
                                "operationId", id,
                                "bindingType", "canonical-json-plan",
                                "ownerType", owner.getClass().getName())));
    }

    static OperationSafetyPolicy safetyPolicy(String id, String sideEffect) {
        OperationSafetyPolicy base = CoreOperationSafetyPolicy.forOperation(id, sideEffect);
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

    static OperationProvenance provenance(String actionName) {
        return OperationProvenance.direct(
                "execution_orchestration", "execute",
                Map.of("suiteType", "regression", "suiteAction", actionName),
                "execution_orchestration_regression_plan_to_direct_suite_request",
                "persisted_run_status_reason_probe_and_report_semantics_preserved",
                "execution_orchestration_regression_" + actionName + "_direct_cde_parity");
    }

}
