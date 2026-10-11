package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.authentication;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.authentication.RouteSynthesisAuthenticationMetadata;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

/**
 * Resolves explicit credentials and inferred requirements while exposing metadata only.
 */
public class DefaultRouteSynthesisAuthenticationResolver
        implements RouteSynthesisAuthenticationResolver {

    private final RouteSynthesisAuthenticationRequirements requirements = new RouteSynthesisAuthenticationRequirements();

    @Override
    public RouteSynthesisAuthenticationMetadata resolve(
            CreateRecipeRequest request, Path projectRoot, Path controllerFile, String endpointPath) {
        RouteSynthesisAuthenticationMetadata required = requirements.resolve(projectRoot, controllerFile, endpointPath);
        if ("not_required".equals(required.status())) {
            return resolve(request);
        }
        if ("basic".equals(required.strategy())) {
            return requiredBasic(request, required);
        }
        if (hasText(request.authToken())) {
            String header = "cookie".equals(required.strategy()) ? "Cookie" : "Authorization";
            return new RouteSynthesisAuthenticationMetadata(
                    "auto_resolved", required.strategy(), List.of(), List.of(header), "input.authToken");
        }
        return required;
    }

    private RouteSynthesisAuthenticationMetadata requiredBasic(
            CreateRecipeRequest request, RouteSynthesisAuthenticationMetadata required) {
        List<String> missing = new ArrayList<>();
        if (!hasText(request.authUsername())) {
            missing.add("authUsername");
        }
        if (!hasText(request.authPassword())) {
            missing.add("authPassword");
        }
        if (missing.isEmpty()) {
            return new RouteSynthesisAuthenticationMetadata(
                    "auto_resolved", "basic", List.of(), List.of("Authorization"), "input.authCredentials");
        }
        return new RouteSynthesisAuthenticationMetadata(
                "needs_user_input", "basic", missing, List.of(), required.source());
    }

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
