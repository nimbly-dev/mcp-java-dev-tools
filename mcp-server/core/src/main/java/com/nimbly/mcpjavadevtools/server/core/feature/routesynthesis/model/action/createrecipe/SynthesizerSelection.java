package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe;

/** Safe deterministic Synthesizer selection result for create_recipe. */
public record SynthesizerSelection(
        boolean compatible,
        boolean externalModulesConfigured,
        int configuredModuleCount,
        String reasonCode) {
}
