package com.nimbly.mcpjavadevtools.server.core.synthesis.springhttp;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.RouteSynthesisHandlerDiscoveryResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.RouteSynthesisHandler;
import com.nimbly.mcpjavadevtools.server.core.synthesis.api.Synthesizer;
import java.util.List;
import java.util.Set;

/** Built-in Spring HTTP compatibility Synthesizer for v0.1.9. */
public class SpringHttpSynthesizer implements Synthesizer {

    /** Returns the stable built-in Synthesizer name. */
    @Override
    public String name() {
        return "spring_http";
    }

    /** Returns the supported extension contract version. */
    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    /** Returns the supported framework identifiers. */
    @Override
    public Set<String> supportedFrameworks() {
        return Set.of("spring_http");
    }

    /** Returns the supported Route Synthesis intent identifiers. */
    @Override
    public Set<String> supportedIntents() {
        return Set.of("line_probe", "regression");
    }

    /** Selects one exact Spring HTTP handler, honoring the source line and rejecting ambiguity. */
    @Override
    public RouteSynthesisSynthesisResult synthesize(
            CreateRecipeRequest request,
            RouteSynthesisHandlerDiscoveryResult discovery) {
        List<RouteSynthesisHandler> candidates = discovery.handlers().stream()
                .filter(candidate -> methodMatches(candidate, request.methodHint()))
                .filter(candidate -> lineMatches(candidate, request.lineHint()))
                .toList();
        if (candidates.isEmpty()) {
            return RouteSynthesisSynthesisResult.failure(
                    "request_candidate_missing", "request_synthesis", "refine_target_hints");
        }
        if (candidates.size() > 1) {
            return RouteSynthesisSynthesisResult.failure(
                    "target_ambiguous", "target_selection", "disambiguate_target");
        }
        return RouteSynthesisSynthesisResult.success(name(), candidates.getFirst());
    }

    private boolean lineMatches(RouteSynthesisHandler handler, Integer line) {
        return line == null || line >= handler.declarationLine() && line <= handler.endLine();
    }

    private boolean methodMatches(RouteSynthesisHandler handler, String methodHint) {
        return methodHint == null || methodHint.isBlank() || handler.methodName().equals(methodHint);
    }
}
