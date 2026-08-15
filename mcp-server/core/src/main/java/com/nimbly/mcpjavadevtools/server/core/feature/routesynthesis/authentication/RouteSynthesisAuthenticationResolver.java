package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.authentication;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.authentication.RouteSynthesisAuthenticationMetadata;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;

/**
 * Authentication resolution boundary for recipe generation.
 */
@FunctionalInterface
public interface RouteSynthesisAuthenticationResolver {

    /**
     * Resolves internal credentials while returning only safe metadata.
     *
     * @param request recipe request
     * @return redacted public metadata
     */
    RouteSynthesisAuthenticationMetadata resolve(CreateRecipeRequest request);
}
