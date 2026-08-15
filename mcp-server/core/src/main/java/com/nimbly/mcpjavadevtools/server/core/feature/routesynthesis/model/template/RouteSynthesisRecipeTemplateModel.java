package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.template;

import java.util.Map;

/** Named, sanitized values exposed to an optional recipe output template. */
public record RouteSynthesisRecipeTemplateModel(Map<String, String> values) {

    /** Defensively copies template values. */
    public RouteSynthesisRecipeTemplateModel {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    /** Returns a value or preserves the token when it is unknown. */
    public String value(String key) {
        return values.getOrDefault(key, "{{" + key + "}}");
    }
}
