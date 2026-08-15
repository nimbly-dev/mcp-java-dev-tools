package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import java.util.List;

/**
 * Typed Route Synthesis recipe request shape for deterministic recipe creation.
 *
 * @param projectRootAbs selected project root
 * @param additionalSourceRoots optional additional source roots
 * @param classHint target class FQCN
 * @param methodHint target method
 * @param lineHint optional target line
 * @param mappingsBaseUrl optional runtime mappings URL
 * @param discoveryPreference runtime discovery preference
 * @param apiBasePath optional API base path
 * @param intentMode execution intent
 * @param authToken optional bearer token
 * @param authUsername optional authentication username
 * @param authPassword optional authentication password
 * @param actuationEnabled optional actuation flag
 * @param actuationReturnBoolean optional actuation result policy
 * @param actuationActuatorId optional actuator identifier
 * @param outputTemplate optional output template
 * @param probeId optional Probe registry selector
 * @param probeBaseUrl optional explicit Probe route
 */
public record CreateRecipeRequest(
        String projectRootAbs,
        List<String> additionalSourceRoots,
        String classHint,
        String methodHint,
        Integer lineHint,
        String mappingsBaseUrl,
        String discoveryPreference,
        String apiBasePath,
        String intentMode,
        String authToken,
        String authUsername,
        String authPassword,
        Boolean actuationEnabled,
        Boolean actuationReturnBoolean,
        String actuationActuatorId,
        String outputTemplate,
        String probeId,
        String probeBaseUrl) implements RouteSynthesisRequest {

    /**
     * Defensively copies optional source roots.
     */
    public CreateRecipeRequest {
        additionalSourceRoots = additionalSourceRoots == null
                ? List.of()
                : List.copyOf(additionalSourceRoots);
    }

    @Override
    public RouteSynthesisAction action() {
        return RouteSynthesisAction.CREATE_RECIPE;
    }
}
