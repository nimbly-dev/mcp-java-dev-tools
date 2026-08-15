package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.template;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.template.RouteSynthesisRecipeTemplateModel;

/** Purpose-owned renderer for deterministic, secret-free recipe templates. */
@FunctionalInterface
public interface RouteSynthesisRecipeTemplateRenderer {

    /** Renders supported tokens and preserves unknown tokens for compatibility. */
    String render(String template, RouteSynthesisRecipeTemplateModel model);
}
