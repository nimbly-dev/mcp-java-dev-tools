package com.nimbly.mcpjavadevtools.server.core.synthesis.registry;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.RouteSynthesisHandlerDiscoveryResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.SynthesizerSelection;

/**
 * Public Core boundary for deterministic Synthesizer compatibility and selection.
 */
@FunctionalInterface
public interface SynthesizerRegistry {

    /** Selects one compatible Synthesizer without reading process configuration. */
    SynthesizerSelection select(CreateRecipeRequest request);

    /** Shapes a recipe using the selected internal compatibility implementation. */
    default RouteSynthesisSynthesisResult synthesize(
            CreateRecipeRequest request,
            RouteSynthesisHandlerDiscoveryResult discovery) {
        return RouteSynthesisSynthesisResult.failure(
                "synthesizer_not_installed", "plugin_selection", "synthesizer_not_installed");
    }
}
