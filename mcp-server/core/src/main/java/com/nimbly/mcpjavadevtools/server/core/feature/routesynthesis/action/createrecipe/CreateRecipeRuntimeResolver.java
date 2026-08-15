package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.createrecipe;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisRecipeCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.RouteSynthesisHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeMappingResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime.RouteSynthesisRuntimeMappingsProvider;
import java.util.List;
import java.util.Optional;

/** Owns runtime mapping selection and its bounded failure guidance. */
public class CreateRecipeRuntimeResolver {

    private final RouteSynthesisRuntimeMappingsProvider mappingsProvider;

    public CreateRecipeRuntimeResolver(RouteSynthesisRuntimeMappingsProvider mappingsProvider) {
        this.mappingsProvider = mappingsProvider;
    }

    /** Resolves runtime mappings only when the request selects a runtime mode. */
    public Optional<RouteSynthesisRuntimeMappingResolution> resolve(CreateRecipeRequest request) {
        if (!"runtime_first".equals(preference(request)) && !"runtime_only".equals(preference(request))) {
            return Optional.empty();
        }
        if (request.mappingsBaseUrl() == null || request.mappingsBaseUrl().isBlank()) {
            return Optional.of(RouteSynthesisRuntimeMappingResolution.failure(
                    "runtime_mappings_input_required", "runtime_mapping_configuration",
                    "provide_mappings_base_url", List.of("mappingsBaseUrl=(missing)"),
                    List.of("spring_runtime_actuator_mappings")));
        }
        return Optional.of(mappingsProvider.resolve(request.mappingsBaseUrl(), request.classHint(),
                request.methodHint(), request.authToken()));
    }

    /** Creates the safe handler envelope for a runtime-selected mapping. */
    public RouteSynthesisHandler handler(CreateRecipeRequest request, RouteSynthesisRecipeCandidate candidate) {
        return new RouteSynthesisHandler(
                candidate.method(), candidate.path(), request.methodHint(), "runtime_mapping",
                request.classHint(), 0, 0, null, "unavailable", "runtime_mapping", null, null);
    }

    /** Maps a stable runtime failure code to bounded user guidance. */
    public String nextAction(String nextActionCode) {
        return switch (nextActionCode) {
            case "provide_mappings_base_url" ->
                    "Provide a valid absolute mappingsBaseUrl and rerun create_recipe.";
            case "verify_runtime_mappings_endpoint" ->
                    "Ensure the actuator mappings endpoint is reachable and rerun create_recipe.";
            case "authorize_runtime_mappings_access" ->
                    "Authorize access to the runtime mappings endpoint and rerun create_recipe.";
            case "verify_runtime_mappings_payload" ->
                    "Return a valid Spring Actuator mappings payload and rerun create_recipe.";
            case "allow_runtime_mappings_host" ->
                    "Use an approved local runtime mappings host and rerun create_recipe.";
            case "refine_runtime_mapping_hints" ->
                    "Refine classHint/methodHint to the exact runtime handler and rerun create_recipe.";
            case "disambiguate_runtime_mapping" ->
                    "Narrow classHint/methodHint until one runtime mapping remains and rerun create_recipe.";
            default -> "Resolve the runtime mapping evidence and rerun create_recipe.";
        };
    }

    private String preference(CreateRecipeRequest request) {
        return request.discoveryPreference() == null ? "static_only" : request.discoveryPreference();
    }
}
