package com.nimbly.mcpjavadevtools.server.core.synthesis.api;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.RouteSynthesisHandlerDiscoveryResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisSynthesisResult;
import java.util.Set;

/**
 * Transport-independent Route Synthesis extension contract.
 */
public interface Synthesizer {

    /** Returns the stable Synthesizer name. */
    String name();

    /** Returns the extension API version implemented by this Synthesizer. */
    String apiVersion();

    /** Returns the framework identifiers supported by this Synthesizer. */
    Set<String> supportedFrameworks();

    /** Returns the Route Synthesis intent identifiers supported by this Synthesizer. */
    Set<String> supportedIntents();

    /** Generates a deterministic recipe outcome from normalized Core input. */
    RouteSynthesisSynthesisResult synthesize(
            CreateRecipeRequest request,
            RouteSynthesisHandlerDiscoveryResult discovery);
}
