package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.authentication;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.authentication.RouteSynthesisAuthenticationMetadata;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves request-provided authentication while exposing metadata only.
 */
public class DefaultRouteSynthesisAuthenticationResolver
        implements RouteSynthesisAuthenticationResolver {

    /** Resolves safe authentication metadata without retaining secret values. */
    @Override
    public RouteSynthesisAuthenticationMetadata resolve(CreateRecipeRequest request) {
        if (hasText(request.authToken())) {
            return new RouteSynthesisAuthenticationMetadata(
                    "auto_resolved", "bearer", List.of(), List.of("Authorization"), "input.authToken");
        }
        if (hasText(request.authUsername()) && hasText(request.authPassword())) {
            return new RouteSynthesisAuthenticationMetadata(
                    "auto_resolved", "basic", List.of(), List.of("Authorization"), "input.authCredentials");
        }
        if (hasText(request.authUsername()) || hasText(request.authPassword())) {
            List<String> missing = new ArrayList<>();
            if (!hasText(request.authUsername())) {
                missing.add("authUsername");
            }
            if (!hasText(request.authPassword())) {
                missing.add("authPassword");
            }
            return new RouteSynthesisAuthenticationMetadata(
                    "needs_user_input", "basic", missing,
                    List.of(), "input.authCredentials");
        }
        return new RouteSynthesisAuthenticationMetadata(
                "not_required", "none", List.of(), List.of(), "none");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
