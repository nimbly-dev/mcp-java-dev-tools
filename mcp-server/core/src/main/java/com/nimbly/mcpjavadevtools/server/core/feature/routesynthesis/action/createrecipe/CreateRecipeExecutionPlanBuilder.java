package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.createrecipe;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.authentication.RouteSynthesisAuthenticationMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisExecutionPlan;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisExecutionStep;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisProbeCallPlan;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisRecipeCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.RouteSynthesisHandler;
import java.util.ArrayList;
import java.util.List;

/** Builds deterministic create_recipe execution plans. */
public class CreateRecipeExecutionPlanBuilder {

    public RouteSynthesisExecutionPlan build(
            CreateRecipeRequest request,
            String selectedMode,
            RouteSynthesisHandler handler,
            RouteSynthesisRecipeCandidate candidate,
            RouteSynthesisAuthenticationMetadata auth) {
        List<RouteSynthesisExecutionStep> steps = new ArrayList<>();
        if ("regression".equals(selectedMode)) {
            steps.add(new RouteSynthesisExecutionStep("execute", "Execute regression API check",
                    requestInstruction(candidate, auth)));
            steps.add(new RouteSynthesisExecutionStep("verify", "Verify API regression outcome",
                    "Validate HTTP code and response assertions for regression checks."));
            return new RouteSynthesisExecutionPlan(selectedMode, "intent_mode_regression", steps,
                    new RouteSynthesisProbeCallPlan(0, "probe_wait_for_hit", false, 0, 0, 0, 0));
        }
        String lineTarget = lineTarget(request, handler);
        if (Boolean.TRUE.equals(request.actuationEnabled())) {
            steps.add(new RouteSynthesisExecutionStep("prepare", "Enable branch actuation",
                    "Call probe actuate arm for " + lineTarget + " returnBoolean="
                            + request.actuationReturnBoolean() + actuatorSuffix(request)));
        }
        steps.add(new RouteSynthesisExecutionStep("prepare", "Reset probe baseline",
                "Call probe reset for " + lineTarget + " before running the trigger request."));
        steps.add(new RouteSynthesisExecutionStep("execute", "Execute probe trigger request",
                requestInstruction(candidate, auth)));
        steps.add(new RouteSynthesisExecutionStep("verify", "Verify single-line probe hit",
                "Require line_hit on " + lineTarget + " using probe wait_for_hit."));
        if (Boolean.TRUE.equals(request.actuationEnabled())) {
            steps.add(new RouteSynthesisExecutionStep("cleanup", "Disable branch actuation",
                    "Call probe actuate disarm for " + lineTarget + actuatorSuffix(request) + "."));
        }
        boolean actuation = Boolean.TRUE.equals(request.actuationEnabled());
        int enableCalls = actuation ? 2 : 0;
        return new RouteSynthesisExecutionPlan(selectedMode, "intent_mode_line_probe", steps,
                new RouteSynthesisProbeCallPlan(2 + enableCalls, "probe_wait_for_hit",
                        actuation, 1, 1, 0, enableCalls));
    }

    private String requestInstruction(
            RouteSynthesisRecipeCandidate candidate, RouteSynthesisAuthenticationMetadata auth) {
        String headers = auth.headers().isEmpty() ? "none" : String.join(",", auth.headers());
        String body = candidate.bodyTemplate() == null ? "" : " body=" + candidate.bodyTemplate();
        return candidate.method() + " " + candidate.fullUrlHint() + " (auth headers: " + headers + ")" + body;
    }

    private String lineTarget(CreateRecipeRequest request, RouteSynthesisHandler handler) {
        int line = request.lineHint() == null ? fallbackLine(handler) : request.lineHint();
        return handler.runtimeClassFqcn() + "#" + handler.methodName() + ":" + line;
    }

    private int fallbackLine(RouteSynthesisHandler handler) {
        return handler.firstExecutableLine() == null ? handler.declarationLine() : handler.firstExecutableLine();
    }

    private String actuatorSuffix(CreateRecipeRequest request) {
        return request.actuationActuatorId() == null ? "" : " actuatorId=" + request.actuationActuatorId();
    }
}
