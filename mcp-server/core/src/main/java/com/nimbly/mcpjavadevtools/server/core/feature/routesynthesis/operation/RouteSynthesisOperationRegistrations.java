package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.RouteSynthesisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.classmethods.ClassMethodsRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.DiscoverHandlersRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.infertarget.InferTargetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import java.util.EnumMap;
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
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;

/** Binds all four Route Synthesis owners to concrete request records. */
public class RouteSynthesisOperationRegistrations {

    private RouteSynthesisOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            RouteSynthesisFeature feature, ObjectMapper mapper) {
        Objects.requireNonNull(feature, "route synthesis feature must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        EnumMap<RouteSynthesisAction, Class<? extends RouteSynthesisRequest>> requests =
                new EnumMap<>(RouteSynthesisAction.class);
        requests.put(RouteSynthesisAction.INFER_TARGET, InferTargetRequest.class);
        requests.put(RouteSynthesisAction.CLASS_METHODS, ClassMethodsRequest.class);
        requests.put(RouteSynthesisAction.DISCOVER_HANDLERS, DiscoverHandlersRequest.class);
        requests.put(RouteSynthesisAction.CREATE_RECIPE, CreateRecipeRequest.class);
        return java.util.Arrays.stream(RouteSynthesisAction.values())
                .<OperationRegistration<?, ?>>map(action ->
                        register(action, requests.get(action), feature, mapper))
                .toList();
    }

    static <I extends RouteSynthesisRequest>
            OperationRegistration<I, RouteSynthesisResult> register(
            RouteSynthesisAction action,
            Class<I> requestType,
            RouteSynthesisFeature feature,
            ObjectMapper mapper) {
        OperationDescriptor descriptor = descriptor(action, requestType, feature);
        return new OperationRegistration<I, RouteSynthesisResult>(
                descriptor,
                requestType,
                RouteSynthesisResult.class,
                new OperationRegistrationContract(
                        schema(action), CoreOperationResultSchemas.route(),
                        CoreOperationSafetyPolicy.forOperation(
                                descriptor.operationId().value(), descriptor.trace().sideEffect())),
                input -> mapper.convertValue(input, requestType),
                input -> feature.execute((RouteSynthesisRequest) input),
                result -> mapper.valueToTree(RouteSynthesisResult.class.cast(result)),
                CoreOperationDirectory.class.getName(),
                identity(action));
    }

    static OperationSchema schema(RouteSynthesisAction action) {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "projectRootAbs");
        ObjectNode roots = root.with("properties").putObject("additionalSourceRoots").put("type", "array");
        roots.put("maxItems", 10).putObject("items").put("type", "string");
        CanonicalOperationSchema.required(root, "projectRootAbs");
        switch (action) {
            case INFER_TARGET -> {
                CanonicalOperationSchema.string(root, "classHint");
                CanonicalOperationSchema.string(root, "methodHint");
                root.with("properties").putObject("lineHint").put("type", "integer").put("minimum", 1);
                root.with("properties").putObject("maxCandidates")
                        .put("type", "integer").put("minimum", 1);
                CanonicalOperationSchema.string(root, "probeId");
                CanonicalOperationSchema.string(root, "probeBaseUrl");
            }
            case CLASS_METHODS, DISCOVER_HANDLERS -> {
                CanonicalOperationSchema.string(root, "classHint");
                CanonicalOperationSchema.string(root, "probeId");
                CanonicalOperationSchema.string(root, "probeBaseUrl");
            }
            case CREATE_RECIPE -> {
                CanonicalOperationSchema.string(root, "classHint");
                CanonicalOperationSchema.string(root, "methodHint");
                root.with("properties").putObject("lineHint").put("type", "integer").put("minimum", 1);
                CanonicalOperationSchema.string(root, "mappingsBaseUrl");
                CanonicalOperationSchema.enumString(root, "discoveryPreference",
                        "static_only", "runtime_first", "runtime_only");
                CanonicalOperationSchema.string(root, "apiBasePath");
                CanonicalOperationSchema.enumString(root, "intentMode", "line_probe", "regression");
                CanonicalOperationSchema.string(root, "authToken");
                CanonicalOperationSchema.string(root, "authUsername");
                CanonicalOperationSchema.string(root, "authPassword");
                CanonicalOperationSchema.booleanValue(root, "actuationEnabled");
                CanonicalOperationSchema.booleanValue(root, "actuationReturnBoolean");
                CanonicalOperationSchema.string(root, "actuationActuatorId");
                CanonicalOperationSchema.string(root, "outputTemplate");
                CanonicalOperationSchema.string(root, "probeId");
                CanonicalOperationSchema.string(root, "probeBaseUrl");
                CanonicalOperationSchema.required(root, "projectRootAbs", "classHint", "methodHint", "intentMode");
            }
        }
        return CanonicalOperationSchema.schema(root);
    }

    static OperationDescriptor descriptor(
            RouteSynthesisAction action,
            Class<? extends RouteSynthesisRequest> requestType,
            RouteSynthesisFeature feature) {
        String id = OperationId.fromLegacy("route_synthesis", action.value()).value();
        String owner = feature.getClass().getName() + "#execute";
        return new OperationDescriptor(
                "route_synthesis", action.value(), requestType.getName(),
                RouteSynthesisResult.class.getName(), owner,
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        RouteSynthesisOperationRegistrations.class.getName(),
                        RouteSynthesisFeature.class.getName(),
                        RouteSynthesisOperationRegistrations.class.getName(),
                        "mcpjvm-610:" + id + ":typed-binding",
                        CoreOperationSafetyPolicy.sideEffect(id),
                        Map.of("featureOwner", feature.getClass().getName(),
                                "operationId", id,
                                "requestType", requestType.getName())));
    }

    static OperationLegacyIdentity identity(RouteSynthesisAction action) {
        return new OperationLegacyIdentity(
                "route_synthesis", action.value(), false, Map.of(),
                "route_synthesis_action_to_concrete_request_record",
                "route_result_envelope_and_typed_action_result_preserved",
                "route_synthesis_" + action.value());
    }
}
