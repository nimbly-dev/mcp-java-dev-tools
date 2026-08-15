package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.template;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.template.RouteSynthesisRecipeTemplateModel;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Default deterministic token renderer matching the TypeScript template behavior. */
public class DefaultRouteSynthesisRecipeTemplateRenderer
        implements RouteSynthesisRecipeTemplateRenderer {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{([A-Za-z0-9_.-]+)}}");

    @Override
    public String render(String template, RouteSynthesisRecipeTemplateModel model) {
        if (template == null || template.isBlank()) {
            return null;
        }
        Matcher matcher = TOKEN.matcher(template);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(output, Matcher.quoteReplacement(model.value(matcher.group(1))));
        }
        matcher.appendTail(output);
        return output.toString();
    }
}
