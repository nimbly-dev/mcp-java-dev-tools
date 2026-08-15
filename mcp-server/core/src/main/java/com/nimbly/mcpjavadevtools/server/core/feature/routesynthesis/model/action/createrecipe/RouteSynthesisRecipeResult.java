package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.authentication.RouteSynthesisAuthenticationMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.RouteSynthesisHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisActionResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetHints;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeCapture;
import java.util.List;

/**
 * Spring HTTP recipe output containing no credential or module path secrets.
 */
public record RouteSynthesisRecipeResult(
        String projectRootAbs,
        RouteTargetHints hints,
        List<String> additionalSourceRoots,
        String applicationType,
        String synthesizerUsed,
        RouteSynthesisHandler selectedHandler,
        List<RouteSynthesisRecipeCandidate> requestCandidates,
        RouteSynthesisExecutionPlan executionPlan,
        String executionReadiness,
        String intentMode,
        RouteSynthesisAuthenticationMetadata auth,
        List<String> evidence,
        List<String> attemptedStrategies,
        RouteSynthesisRuntimeCapture runtimeCapture,
        String rendered) implements RouteSynthesisActionResult {

    /** Defensively copies recipe collections. */
    public RouteSynthesisRecipeResult {
        additionalSourceRoots = copy(additionalSourceRoots);
        requestCandidates = copy(requestCandidates);
        evidence = copy(evidence);
        attemptedStrategies = copy(attemptedStrategies);
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
