package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.createrecipe;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.authentication.RouteSynthesisAuthenticationMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisExecutionPlan;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisExecutionStep;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisRecipeCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.template.RouteSynthesisRecipeTemplateModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the TypeScript-compatible output-template token model. */
public class CreateRecipeTemplateModelBuilder {

    public RouteSynthesisRecipeTemplateModel build(
            CreateRecipeRequest request,
            RouteSynthesisRecipeCandidate candidate,
            String selectedMode,
            RouteSynthesisAuthenticationMetadata auth,
            RouteSynthesisExecutionPlan plan) {
        String body = candidate.bodyTemplate() == null ? "" : " body=" + candidate.bodyTemplate();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("target.path", request.classHint() + "#" + request.methodHint());
        values.put("target.class", request.classHint());
        values.put("target.method", request.methodHint());
        values.put("target.line_hint", request.lineHint() == null ? "Not provided"
                : request.lineHint().toString());
        values.put("probe.key", request.classHint() + "#" + request.methodHint());
        values.put("probe.hit", "Not executed (recipe only)");
        values.put("http.request", candidate.method() + " " + candidate.fullUrlHint() + body);
        values.put("http.method", candidate.method());
        values.put("http.path", candidate.path());
        values.put("http.query", candidate.queryTemplate());
        values.put("http.code", "Not executed");
        values.put("http.response", "Not executed");
        values.put("execution_hit", "not_executed");
        values.put("api_outcome", "not_executed");
        values.put("repro_status", "ready");
        values.put("auth.required", Boolean.toString(!"not_required".equals(auth.status())));
        values.put("auth.status", auth.status());
        values.put("auth.strategy", auth.strategy());
        values.put("auth.next_action", authenticationNextAction(auth));
        values.put("auth.headers", auth.headers().isEmpty() ? "Not provided" : String.join(",", auth.headers()));
        values.put("auth.missing", auth.missing().isEmpty() ? "-" : String.join(",", auth.missing()));
        values.put("auth.source", auth.source() == null ? "Not resolved" : auth.source());
        values.put("auth.login.path", "Not inferred");
        values.put("auth.login.body", "Not inferred");
        values.put("recipe.mode", selectedMode);
        values.put("recipe.mode_reason", "intent_mode_" + request.intentMode());
        values.put("recipe.steps", formatSteps(plan.steps()));
        values.put("run.duration", "Not measured");
        values.put("run.notes", "result_type=recipe | status=ready | selected_mode=" + selectedMode
                + " | routing_reason=" + plan.routingReason());
        return new RouteSynthesisRecipeTemplateModel(values);
    }

    private String formatSteps(List<RouteSynthesisExecutionStep> steps) {
        if (steps.isEmpty()) {
            return "No steps available.";
        }
        StringBuilder formatted = new StringBuilder();
        for (int index = 0; index < steps.size(); index++) {
            if (index > 0) {
                formatted.append(System.lineSeparator());
            }
            RouteSynthesisExecutionStep step = steps.get(index);
            formatted.append(index + 1).append(". [").append(step.phase()).append("] ")
                    .append(step.title()).append(System.lineSeparator()).append("   ")
                    .append(step.instruction());
        }
        return formatted.toString();
    }

    private String authenticationNextAction(RouteSynthesisAuthenticationMetadata auth) {
        return switch (auth.status()) {
            case "auto_resolved" -> "Use the resolved authentication metadata in the generated request.";
            case "needs_user_input" -> "Provide the missing authentication inputs before execution.";
            default -> "No authentication requirement inferred for this route.";
        };
    }
}
