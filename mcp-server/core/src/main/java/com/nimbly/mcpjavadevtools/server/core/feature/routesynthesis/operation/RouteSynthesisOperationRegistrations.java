package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.DefaultRouteSynthesisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.RouteSynthesisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.RouteSynthesisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.classmethods.ClassMethodsRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.DiscoverHandlersRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.infertarget.InferTargetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionContext;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CoreOperationResultSchemas;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationProvenance;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;

/** Binds all four Route Synthesis owners to concrete request records. */
public class RouteSynthesisOperationRegistrations {

    private RouteSynthesisOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            RouteSynthesisFeature feature, ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        var defaultFeature = (DefaultRouteSynthesisFeature) Objects.requireNonNull(feature, "feature must not be null");
        return List.of(
                register(RouteSynthesisAction.INFER_TARGET, InferTargetRequest.class, defaultFeature, mapper),
                register(RouteSynthesisAction.CLASS_METHODS, ClassMethodsRequest.class, defaultFeature, mapper),
                register(RouteSynthesisAction.DISCOVER_HANDLERS, DiscoverHandlersRequest.class, defaultFeature, mapper),
                register(RouteSynthesisAction.CREATE_RECIPE, CreateRecipeRequest.class, defaultFeature, mapper));
    }

    static <I extends RouteSynthesisRequest> OperationRegistration<I, RouteSynthesisResult> register(
            RouteSynthesisAction action, Class<I> requestType,
            DefaultRouteSynthesisFeature feature, ObjectMapper mapper) {
        RouteSynthesisActionHandler owner = feature.operationOwner(action);
        OperationDescriptor descriptor = descriptor(action, requestType, owner);
        return new OperationRegistration<I, RouteSynthesisResult>(
                descriptor,
                requestType,
                RouteSynthesisResult.class,
                new OperationRegistrationContract(
                        RouteSynthesisOperationSchemas.forAction(action), CoreOperationResultSchemas.route(),
                        CoreOperationSafetyPolicy.forOperation(
                                descriptor.operationId().value(), descriptor.trace().sideEffect())),
                OperationRequestDecoders.typedWithDefaults(mapper.copy()
                                .setSerializationInclusion(JsonInclude.Include.NON_NULL), requestType,
                        Map.of("additionalSourceRoots", mapper.createArrayNode())),
                ContextAwareOperationExecutor.declared(OperationCancellationState.CONTEXT_AWARE_CANCELLATION,
                        OperationCancellationGuarantee.IDEMPOTENT,
                        (input, context) -> {
                            checkpoint(context);
                            RouteSynthesisResult result = owner.execute(input);
                            checkpoint(context);
                            return result;
                        }),
                new RouteSynthesisResultEncoder(
                        action, mapper, OperationResultEncoders.typed(mapper, RouteSynthesisResult.class)),
                CoreOperationDirectory.class.getName(),
                identity(action));
    }

    static OperationDescriptor descriptor(RouteSynthesisAction action,
            Class<? extends RouteSynthesisRequest> requestType, RouteSynthesisActionHandler owner) {
        String id = OperationId.fromLegacy("route_synthesis", action.value()).value();
        String executableOwner = owner.getClass().getName() + "#execute";
        return new OperationDescriptor(
                "route_synthesis", action.value(), requestType.getName(),
                RouteSynthesisResult.class.getName(), executableOwner,
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        RouteSynthesisOperationRegistrations.class.getName(),
                        RouteSynthesisActionHandler.class.getName(),
                        RouteSynthesisOperationRegistrations.class.getName(),
                        "mcpjvm-610:" + id + ":typed-binding",
                        CoreOperationSafetyPolicy.sideEffect(id),
                        Map.of("executableOwner", executableOwner,
                                "operationId", id,
                                "requestType", requestType.getName())));
    }

    static OperationProvenance identity(RouteSynthesisAction action) {
        boolean recipe = action == RouteSynthesisAction.CREATE_RECIPE;
        String normalization = recipe
                ? "create_recipe.synthesizerUsed:java_owner=spring_http->cde=spring=typescript_released_spring"
                : "route_synthesis_action_to_concrete_request_record";
        String comparison = recipe
                ? "MCPJVM-621_approved_cde_spring_mapping_and_recipe_semantics"
                : "typed_action_result_preserved";
        return OperationProvenance.released(
                "route_synthesis", action.value(), Map.of(),
                normalization,
                "route_result_envelope_and_" + comparison,
                "route_synthesis_" + action.value());
    }

    private static void checkpoint(OperationExecutionContext context) {
        if (Thread.currentThread().isInterrupted() || context.cancellationRequested()) {
            throw new java.util.concurrent.CancellationException("route synthesis operation was cancelled");
        }
    }
}
