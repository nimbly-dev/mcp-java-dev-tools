package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.createrecipe;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisInputPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisPathPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Validates create_recipe input and resolves bounded source roots. */
public class CreateRecipeInputValidator {

    /** Returns the first deterministic input validation report, if any. */
    public Optional<RouteSynthesisResult> validate(CreateRecipeRequest request) {
        if (request.projectRootAbs() == null || request.projectRootAbs().isBlank()
                || request.classHint() == null || request.classHint().isBlank()) {
            return Optional.of(report("target_not_inferred", "target_candidate_missing", "target_inference",
                    "refine_target_hints", "Refine classHint or methodHint and rerun create_recipe."));
        }
        Optional<RouteSynthesisResult> required = validateRequired(request);
        if (required.isPresent()) {
            return required;
        }
        Optional<RouteSynthesisResult> options = validateOptions(request);
        if (options.isPresent()) {
            return options;
        }
        if (!isFullyQualifiedClassName(request.classHint())) {
            return Optional.of(report("target_not_inferred", "class_hint_not_fqcn", "target_inference",
                    "provide_class_fqcn", "Provide classHint as an exact fully qualified class name."));
        }
        if (!RouteSynthesisInputPolicy.additionalRootsWithinBound(request.additionalSourceRoots())) {
            return Optional.of(report("project_selector_invalid", "additional_source_roots_limit_exceeded",
                    "additional_source_roots_validation", "reduce_additional_source_roots",
                    "Provide no more than 10 additionalSourceRoots."));
        }
        return Optional.empty();
    }

    /** Resolves only existing roots contained by the bound workspace. */
    public Optional<List<Path>> resolveAdditionalRoots(
            CreateRecipeRequest request,
            RouteSynthesisWorkspaceSnapshot workspace) {
        List<Path> roots = new ArrayList<>();
        for (String value : request.additionalSourceRoots()) {
            Optional<Path> resolved = RouteSynthesisPathPolicy.resolveExistingDirectory(
                    workspace.workspaceRoot(), value);
            if (resolved.isEmpty()) {
                return Optional.empty();
            }
            roots.add(resolved.get());
        }
        return Optional.of(List.copyOf(roots));
    }

    private Optional<RouteSynthesisResult> validateRequired(CreateRecipeRequest request) {
        if (request.methodHint() == null || request.methodHint().isBlank()) {
            return Optional.of(report("blocked_invalid", "method_hint_required", "input_validation",
                    "provide_method_hint", "Provide methodHint as the exact handler method name."));
        }
        if (request.intentMode() == null || request.intentMode().isBlank()) {
            return Optional.of(report("blocked_invalid", "intent_mode_required", "input_validation",
                    "provide_intent_mode", "Provide intentMode as line_probe or regression."));
        }
        if (!List.of("line_probe", "regression").contains(request.intentMode())) {
            return Optional.of(report("blocked_invalid", "intent_mode_invalid", "input_validation",
                    "provide_intent_mode", "Provide intentMode as line_probe or regression."));
        }
        if ("line_probe".equals(request.intentMode()) && request.lineHint() == null) {
            return Optional.of(report("target_not_inferred", "line_target_required_for_probe_mode", "intent_routing",
                    "provide_line_hint", "Provide lineHint for strict line_probe verification."));
        }
        return Optional.empty();
    }

    private Optional<RouteSynthesisResult> validateOptions(CreateRecipeRequest request) {
        if (request.discoveryPreference() != null
                && !List.of("static_only", "runtime_first", "runtime_only")
                        .contains(request.discoveryPreference())) {
            return Optional.of(report("blocked_invalid", "discovery_preference_invalid", "input_validation",
                    "provide_discovery_preference", "Provide a supported discoveryPreference."));
        }
        if (request.lineHint() != null && request.lineHint() < 1) {
            return Optional.of(report("blocked_invalid", "line_hint_invalid", "input_validation",
                    "provide_positive_line_hint", "Provide lineHint as a positive integer."));
        }
        if (Boolean.TRUE.equals(request.actuationEnabled())
                && "line_probe".equals(request.intentMode())
                && request.actuationReturnBoolean() == null) {
            return Optional.of(report("execution_input_required", "actuation_input_required", "actuation_resolution",
                    "provide_actuation_input", "Provide actuationReturnBoolean when actuationEnabled is true."));
        }
        return Optional.empty();
    }

    private RouteSynthesisResult report(
            String status, String reason, String step, String nextActionCode, String nextAction) {
        return RouteSynthesisResult.report(status, reason, step, nextActionCode, nextAction);
    }

    private boolean isFullyQualifiedClassName(String value) {
        return value.indexOf('.') > 0 && !value.endsWith(".") && !value.contains(" ");
    }
}
