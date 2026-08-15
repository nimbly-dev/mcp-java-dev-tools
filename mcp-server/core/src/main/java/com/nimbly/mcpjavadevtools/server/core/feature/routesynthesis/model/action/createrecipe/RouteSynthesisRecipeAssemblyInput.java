package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.RouteSynthesisHandlerDiscoveryResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeMappingResolution;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Immutable Core input assembled for one create_recipe result. */
public record RouteSynthesisRecipeAssemblyInput(
        Path projectRoot,
        CreateRecipeRequest request,
        List<Path> roots,
        RouteSynthesisHandlerDiscoveryResult discovery,
        RouteSynthesisSynthesisResult synthesized,
        Optional<RouteSynthesisRuntimeMappingResolution> runtime) {
}
