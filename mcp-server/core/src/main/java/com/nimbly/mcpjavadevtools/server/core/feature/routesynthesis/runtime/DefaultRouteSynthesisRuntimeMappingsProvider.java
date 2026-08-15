package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeMappingResolution;

import java.util.List;

/** Fail-closed default used by Core-only tests without an Application HTTP adapter. */
public class DefaultRouteSynthesisRuntimeMappingsProvider
        implements RouteSynthesisRuntimeMappingsProvider {

    @Override
    public RouteSynthesisRuntimeMappingResolution resolve(
            String mappingsBaseUrl,
            String classHint,
            String methodHint,
            String authToken) {
        return RouteSynthesisRuntimeMappingResolution.failure(
                "runtime_mappings_unreachable", "runtime_mapping_fetch", "verify_runtime_mappings_endpoint",
                List.of("runtimeMappingsProvider=unconfigured"), List.of("spring_runtime_actuator_mappings"));
    }
}
