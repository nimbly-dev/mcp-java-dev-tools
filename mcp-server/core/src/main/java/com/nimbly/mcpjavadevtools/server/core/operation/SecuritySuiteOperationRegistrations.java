package com.nimbly.mcpjavadevtools.server.core.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.SecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.action.SecuritySuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.request.SecuritySuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.result.SecuritySuiteResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Binds the Security Suite action to its bounded JSON contract. */
public final class SecuritySuiteOperationRegistrations {

    private SecuritySuiteOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            SecuritySuiteFeature feature, ObjectMapper mapper) {
        Objects.requireNonNull(feature, "security suite feature must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        return List.<OperationRegistration<?, ?>>of(register(feature, mapper));
    }

    static OperationRegistration<SuiteOperationArguments, SecuritySuiteResult> register(
            SecuritySuiteFeature feature, ObjectMapper mapper) {
        String id = "security_suite.execute_plan";
        OperationDescriptor descriptor = new OperationDescriptor(
                "security_suite", "execute_plan", SuiteOperationArguments.class.getName(),
                SecuritySuiteResult.class.getName(), feature.getClass().getName() + "#execute",
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        SecuritySuiteOperationRegistrations.class.getName(),
                        SecuritySuiteFeature.class.getName(),
                        SecuritySuiteOperationRegistrations.class.getName(),
                        "mcpjvm-610:" + id + ":typed-binding",
                        "filesystem_write",
                        Map.of("featureOwner", feature.getClass().getName(),
                                "operationId", id,
                                "bindingType", "canonical-json-plan")));
        return new OperationRegistration<>(
                descriptor,
                SuiteOperationArguments.class,
                SecuritySuiteResult.class,
                new OperationRegistrationContract(
                        schema(), CoreOperationResultSchemas.suite(),
                        CoreOperationSafetyPolicy.forOperation(id, "filesystem_write")),
                input -> new SuiteOperationArguments(input),
                input -> feature.execute(new SecuritySuiteRequest(
                        SecuritySuiteAction.EXECUTE_PLAN, input.input())),
                result -> mapper.valueToTree(SecuritySuiteResult.class.cast(result)),
                CoreOperationDirectory.class.getName(),
                new OperationLegacyIdentity(
                        "security_suite", "execute_plan", false, Map.of(),
                        "security_suite_plan_json_to_feature_request",
                        "security_suite_status_reason_next_action_and_details_preserved",
                        "security_suite_execute_plan_public_request_contract"));
    }

    static OperationSchema schema() {
        ObjectNode root = CanonicalOperationSchema.object();
        root.with("properties").putObject("metadata").put("type", "object").put("additionalProperties", true);
        root.with("properties").putObject("contract").put("type", "object").put("additionalProperties", true);
        root.with("properties").putObject("credentialBindings")
                .put("type", "object").put("additionalProperties", true);
        root.with("properties").putObject("credentialSource")
                .put("type", "object").put("additionalProperties", true);
        CanonicalOperationSchema.string(root, "projectName");
        CanonicalOperationSchema.string(root, "planName");
        CanonicalOperationSchema.string(root, "suiteRunId");
        CanonicalOperationSchema.string(root, "workspaceRootAbs");
        CanonicalOperationSchema.required(root, "contract");
        return CanonicalOperationSchema.schema(root);
    }
}
