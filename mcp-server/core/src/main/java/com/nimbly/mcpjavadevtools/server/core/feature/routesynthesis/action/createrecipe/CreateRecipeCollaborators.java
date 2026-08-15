package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.createrecipe;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.routing.RouteSynthesisProbeRouteResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime.RouteSynthesisRuntimeEvidenceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime.RouteSynthesisRuntimeMappingsProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.template.RouteSynthesisRecipeTemplateRenderer;

/** Purpose-owned operational collaborators for create_recipe composition. */
public class CreateRecipeCollaborators {

    private final RouteSynthesisRuntimeMappingsProvider runtimeMappingsProvider;
    private final RouteSynthesisRuntimeEvidenceProvider runtimeEvidenceProvider;
    private final RouteSynthesisRecipeTemplateRenderer templateRenderer;
    private final RouteSynthesisProbeRouteResolver probeRouteResolver;

    /** Creates the create_recipe composition. */
    public CreateRecipeCollaborators(
            RouteSynthesisRuntimeMappingsProvider runtimeMappingsProvider,
            RouteSynthesisRuntimeEvidenceProvider runtimeEvidenceProvider,
            RouteSynthesisRecipeTemplateRenderer templateRenderer,
            RouteSynthesisProbeRouteResolver probeRouteResolver) {
        this.runtimeMappingsProvider = runtimeMappingsProvider;
        this.runtimeEvidenceProvider = runtimeEvidenceProvider;
        this.templateRenderer = templateRenderer;
        this.probeRouteResolver = probeRouteResolver;
    }

    public RouteSynthesisRuntimeMappingsProvider runtimeMappingsProvider() {
        return runtimeMappingsProvider;
    }

    public RouteSynthesisRuntimeEvidenceProvider runtimeEvidenceProvider() {
        return runtimeEvidenceProvider;
    }

    public RouteSynthesisRecipeTemplateRenderer templateRenderer() {
        return templateRenderer;
    }

    public RouteSynthesisProbeRouteResolver probeRouteResolver() {
        return probeRouteResolver;
    }
}
