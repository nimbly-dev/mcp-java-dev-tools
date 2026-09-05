package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.PerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.action.PerformanceSuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.operation.PerformanceSuiteOperationArguments;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.request.PerformanceSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.result.PerformanceSuiteResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

/** Binds the Performance Suite action to its bounded JSON plan contract. */
public class PerformanceSuiteOperationRegistrations {

    private PerformanceSuiteOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            PerformanceSuiteFeature feature, ObjectMapper mapper) {
        Objects.requireNonNull(feature, "performance suite feature must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        return List.<OperationRegistration<?, ?>>of(register(feature, mapper));
    }

    static OperationRegistration<PerformanceSuiteOperationArguments, PerformanceSuiteResult> register(
            PerformanceSuiteFeature feature, ObjectMapper mapper) {
        String id = "performance_suite.execute_plan";
        OperationDescriptor descriptor = new OperationDescriptor(
                "performance_suite", "execute_plan", PerformanceSuiteOperationArguments.class.getName(),
                PerformanceSuiteResult.class.getName(), feature.getClass().getName() + "#execute",
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        PerformanceSuiteOperationRegistrations.class.getName(),
                        PerformanceSuiteFeature.class.getName(),
                        PerformanceSuiteOperationRegistrations.class.getName(),
                        "mcpjvm-610:" + id + ":typed-binding",
                        "filesystem_write",
                        Map.of("featureOwner", feature.getClass().getName(),
                                "operationId", id,
                                "bindingType", "canonical-json-plan")));
        return new OperationRegistration<>(
                descriptor,
                PerformanceSuiteOperationArguments.class,
                PerformanceSuiteResult.class,
                new OperationRegistrationContract(
                        schema(), CoreOperationResultSchemas.suite(),
                        CoreOperationSafetyPolicy.forOperation(id, "filesystem_write")),
                input -> new PerformanceSuiteOperationArguments(input),
                input -> feature.execute(new PerformanceSuiteRequest(
                        PerformanceSuiteAction.EXECUTE_PLAN, input.input())),
                result -> mapper.valueToTree(PerformanceSuiteResult.class.cast(result)),
                CoreOperationDirectory.class.getName(),
                new OperationLegacyIdentity(
                        "performance_suite", "execute_plan", false, Map.of(),
                        "performance_suite_plan_json_to_feature_request",
                        "performance_suite_status_reason_next_action_and_details_preserved",
                        "performance_suite_execute_plan_public_request_contract"));
    }

    static OperationSchema schema() {
        ObjectNode root = CanonicalOperationSchema.object();
        root.with("properties").putObject("metadata").put("type", "object").put("additionalProperties", true);
        root.with("properties").putObject("contract").put("type", "object").put("additionalProperties", true);
        root.with("properties").putObject("request").put("type", "object").put("additionalProperties", true);
        root.with("properties").putObject("providedContext")
                .put("type", "object").put("additionalProperties", true);
        CanonicalOperationSchema.string(root, "projectName");
        CanonicalOperationSchema.string(root, "planName");
        CanonicalOperationSchema.string(root, "runDirectory");
        CanonicalOperationSchema.string(root, "suiteRunId");
        CanonicalOperationSchema.string(root, "probeBaseUrl");
        CanonicalOperationSchema.string(root, "workspaceRootAbs");
        CanonicalOperationSchema.required(root, "contract");
        return CanonicalOperationSchema.schema(root);
    }
}
