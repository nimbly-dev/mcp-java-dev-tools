package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeMappingResolution;

/** Purpose-owned boundary for bounded Spring runtime mapping resolution. */
@FunctionalInterface
public interface RouteSynthesisRuntimeMappingsProvider {

    /** Resolves one runtime mapping without exposing transport or HTTP details to Core. */
    RouteSynthesisRuntimeMappingResolution resolve(
            String mappingsBaseUrl,
            String classHint,
            String methodHint,
            String authToken);
}
