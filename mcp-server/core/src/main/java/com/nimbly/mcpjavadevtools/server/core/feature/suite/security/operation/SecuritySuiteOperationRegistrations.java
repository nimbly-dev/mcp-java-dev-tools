package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.SecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.TrustedSuiteExecution;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.action.SecuritySuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.operation.SecuritySuiteOperationArguments;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.request.SecuritySuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.result.SecuritySuiteResult;
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

/** Binds the direct Security Suite CDE operation to its substantive action owner. */
public class SecuritySuiteOperationRegistrations {

    private static final String OPERATION_ID = "security_suite.execute_plan";

    private SecuritySuiteOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            SecuritySuiteFeature feature, ObjectMapper mapper) {
        return createTrusted(feature, mapper, null);
    }

    /** Binds a workspace-backed direct CDE execution context. */
    static List<OperationRegistration<?, ?>> createTrusted(
            SecuritySuiteFeature feature, ObjectMapper mapper, TrustedSuiteExecution trusted) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        SecuritySuiteFeature owner = Objects.requireNonNull(
                feature, "security suite feature must not be null");
        return List.of(register(owner, mapper, trusted));
    }

    static OperationRegistration<SecuritySuiteOperationArguments, SecuritySuiteResult> register(
            SecuritySuiteFeature owner, ObjectMapper mapper, TrustedSuiteExecution trusted) {
        String executableOwner = owner.getClass().getName() + "#execute";
        OperationDescriptor descriptor = descriptor(executableOwner, owner);
        return new OperationRegistration<>(
                descriptor,
                SecuritySuiteOperationArguments.class,
                SecuritySuiteResult.class,
                new OperationRegistrationContract(
                        schema(), CoreOperationResultSchemas.suite(),
                        safetyPolicy()),
                OperationRequestDecoders.wrap(
                        mapper, SecuritySuiteOperationArguments.class,
                        SecuritySuiteOperationArguments::new,
                        SecuritySuiteOperationArguments::input),
                ContextAwareOperationExecutor.declared(OperationCancellationState.NOT_CANCELLABLE,
                        OperationCancellationGuarantee.DETERMINISTIC_CONTINUATION,
                        (input, context) -> trusted == null
                                ? SecuritySuiteResult.blocked("direct_suite_context_unavailable",
                                        "bind a trusted Artifact workspace before direct execution", Map.of())
                                : trusted.execute("security", input.input(), SecuritySuiteResult.class,
                                        resolved -> owner.execute(new SecuritySuiteRequest(
                                                SecuritySuiteAction.EXECUTE_PLAN, resolved)),
                                        code -> SecuritySuiteResult.blocked(code,
                                                "inspect the persisted plan and workspace", Map.of()), true)),
                OperationResultEncoders.typed(mapper, SecuritySuiteResult.class),
                CoreOperationDirectory.class.getName(),
                provenance());
    }

    static OperationDescriptor descriptor(String executableOwner, SecuritySuiteFeature owner) {
        return new OperationDescriptor(
                "security_suite", "execute_plan", SecuritySuiteOperationArguments.class.getName(),
                SecuritySuiteResult.class.getName(), executableOwner,
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        SecuritySuiteOperationRegistrations.class.getName(),
                        SecuritySuiteFeature.class.getName(),
                        SecuritySuiteOperationRegistrations.class.getName(),
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
                Map.of("suiteType", "security", "suiteAction", "execute_plan"),
                "execution_orchestration_security_plan_to_direct_suite_request",
                "persisted_run_status_coverage_findings_probe_and_redaction_semantics_preserved",
                "execution_orchestration_security_execute_plan_direct_cde_parity");
    }

}
