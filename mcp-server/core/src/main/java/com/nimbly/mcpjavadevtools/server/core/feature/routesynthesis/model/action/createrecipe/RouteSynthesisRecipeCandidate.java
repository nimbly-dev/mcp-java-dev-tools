package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe;

import java.util.List;

/**
 * Safe HTTP request candidate produced by a compatible Synthesizer.
 */
public record RouteSynthesisRecipeCandidate(
        String method,
        String path,
        String queryTemplate,
        String fullUrlHint,
        String bodyTemplate,
        List<String> assumptions,
        List<String> needsConfirmation,
        List<String> rationale) {

    /** Defensively copies assumptions. */
    public RouteSynthesisRecipeCandidate {
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        needsConfirmation = needsConfirmation == null ? List.of() : List.copyOf(needsConfirmation);
        rationale = rationale == null ? List.of() : List.copyOf(rationale);
    }
}
