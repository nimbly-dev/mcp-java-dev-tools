package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.classmethods;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.classmethods.ClassMethodsRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisInputPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisPathPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Owns class_methods input validation and contained workspace path resolution. */
public class ClassMethodsRequestPolicy {

    /** Validates the request fields owned by class_methods. */
    public Optional<RouteSynthesisResult> validate(ClassMethodsRequest request) {
        if (request.projectRootAbs() == null || request.projectRootAbs().isBlank()) {
            return Optional.of(RouteSynthesisResult.report(
                    "project_selector_required", "project_selector_required", "project_root_validation",
                    "provide_project_root", "Provide projectRootAbs as an existing contained project directory."));
        }
        if (request.classHint() == null || request.classHint().isBlank()) {
            return Optional.of(RouteSynthesisResult.report(
                    "class_hint_required", "class_hint_required", "input_validation", "provide_class_hint",
                    "Provide classHint and rerun class_methods."));
        }
        if (!RouteSynthesisInputPolicy.additionalRootsWithinBound(request.additionalSourceRoots())) {
            return Optional.of(RouteSynthesisResult.report(
                    "project_selector_invalid", "additional_source_roots_limit_exceeded",
                    "additional_source_roots_validation", "reduce_additional_source_roots",
                    "Provide no more than 10 additionalSourceRoots."));
        }
        return Optional.empty();
    }

    /** Resolves the contained project root. */
    public Optional<Path> resolveProjectRoot(
            ClassMethodsRequest request,
            RouteSynthesisWorkspaceSnapshot workspace) {
        return RouteSynthesisPathPolicy.resolveExistingDirectory(
                workspace.workspaceRoot(), request.projectRootAbs());
    }

    /** Resolves all additional source roots while enforcing workspace containment. */
    public Optional<List<Path>> resolveAdditionalRoots(
            ClassMethodsRequest request,
            RouteSynthesisWorkspaceSnapshot workspace) {
        List<Path> roots = new ArrayList<>();
        for (String requested : request.additionalSourceRoots()) {
            Optional<Path> resolved = RouteSynthesisPathPolicy.resolveExistingDirectory(
                    workspace.workspaceRoot(), requested);
            if (resolved.isEmpty()) {
                return Optional.empty();
            }
            roots.add(resolved.get());
        }
        return Optional.of(List.copyOf(roots));
    }
}
