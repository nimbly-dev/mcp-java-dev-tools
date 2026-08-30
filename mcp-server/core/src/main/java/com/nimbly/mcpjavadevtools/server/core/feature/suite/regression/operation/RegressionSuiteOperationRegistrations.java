package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.RegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.action.RegressionSuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.operation.RegressionSuiteOperationArguments;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.request.RegressionSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result.RegressionSuiteResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
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

/** Binds both Regression Suite actions to the existing Core JSON plan contract. */
public final class RegressionSuiteOperationRegistrations {

    private RegressionSuiteOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            RegressionSuiteFeature feature, ObjectMapper mapper) {
        Objects.requireNonNull(feature, "regression suite feature must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        return List.<OperationRegistration<?, ?>>of(
                register(RegressionSuiteAction.PREFLIGHT, feature, mapper),
                register(RegressionSuiteAction.EXECUTE_PLAN, feature, mapper));
    }

    static OperationRegistration<RegressionSuiteOperationArguments, RegressionSuiteResult> register(
            RegressionSuiteAction action, RegressionSuiteFeature feature, ObjectMapper mapper) {
        String actionName = actionName(action);
        String id = OperationId.fromLegacy("regression_suite", actionName).value();
        OperationDescriptor descriptor = new OperationDescriptor(
                "regression_suite", actionName, RegressionSuiteOperationArguments.class.getName(),
                RegressionSuiteResult.class.getName(), feature.getClass().getName() + "#execute",
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        RegressionSuiteOperationRegistrations.class.getName(),
                        RegressionSuiteFeature.class.getName(),
                        RegressionSuiteOperationRegistrations.class.getName(),
                        "mcpjvm-610:" + id + ":typed-binding",
                        CoreOperationSafetyPolicy.sideEffect(id),
                        Map.of("featureOwner", feature.getClass().getName(),
                                "operationId", id,
                                "bindingType", "canonical-json-plan")));
        return new OperationRegistration<>(
                descriptor,
                RegressionSuiteOperationArguments.class,
                RegressionSuiteResult.class,
                new OperationRegistrationContract(
                        schema(action), CoreOperationResultSchemas.suite(),
                        CoreOperationSafetyPolicy.forOperation(id, descriptor.trace().sideEffect())),
                input -> new RegressionSuiteOperationArguments(input),
                input -> feature.execute(new RegressionSuiteRequest(
                        action, input.input())),
                result -> mapper.valueToTree(RegressionSuiteResult.class.cast(result)),
                CoreOperationDirectory.class.getName(),
                identity(action));
    }

    static OperationSchema schema(RegressionSuiteAction action) {
        ObjectNode root = CanonicalOperationSchema.object();
        root.with("properties").putObject("metadata").put("type", "object").put("additionalProperties", true);
        root.with("properties").putObject("contract").put("type", "object").put("additionalProperties", true);
        root.with("properties").putObject("providedContext")
                .put("type", "object").put("additionalProperties", true);
        CanonicalOperationSchema.string(root, "projectName");
        CanonicalOperationSchema.string(root, "planName");
        CanonicalOperationSchema.string(root, "runDirectory");
        CanonicalOperationSchema.string(root, "suiteRunId");
        CanonicalOperationSchema.string(root, "probeBaseUrl");
        CanonicalOperationSchema.string(root, "workspaceRootAbs");
        CanonicalOperationSchema.required(root, "metadata", "contract");
        if (action == RegressionSuiteAction.EXECUTE_PLAN) {
            root.with("properties").putObject("runtimeContextName").put("type", "string");
        }
        return CanonicalOperationSchema.schema(root);
    }

    static OperationLegacyIdentity identity(RegressionSuiteAction action) {
        return new OperationLegacyIdentity(
                "regression_suite", actionName(action), false, Map.of(),
                "regression_suite_plan_json_to_feature_request",
                "regression_suite_status_reason_next_action_and_details_preserved",
                "regression_suite_" + actionName(action));
    }

    private static String actionName(RegressionSuiteAction action) {
        return action.name().toLowerCase(Locale.ROOT);
    }
}
