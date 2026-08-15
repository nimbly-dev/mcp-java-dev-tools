package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.discoverhandlers;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.DiscoverHandlersRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisInputPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisPathPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Owns discover_handlers validation and contained workspace path resolution. */
public class DiscoverHandlersRequestPolicy {

    /** Validates the request fields owned by discover_handlers. */
    public Optional<RouteSynthesisResult> validate(DiscoverHandlersRequest request) {
        if (request.projectRootAbs() == null || request.projectRootAbs().isBlank()) {
            return Optional.of(RouteSynthesisResult.report(
                    "project_selector_required", "project_selector_required", "project_root_validation",
                    "provide_project_root", "Provide projectRootAbs and rerun discover_handlers."));
        }
        if (!isFullyQualifiedClassName(request.classHint())) {
            return Optional.of(RouteSynthesisResult.report(
                    "class_hint_required", "class_hint_not_fqcn", "input_validation", "provide_class_fqcn",
                    "Provide classHint as an exact fully qualified controller class name."));
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
            DiscoverHandlersRequest request,
            RouteSynthesisWorkspaceSnapshot workspace) {
        return RouteSynthesisPathPolicy.resolveExistingDirectory(
                workspace.workspaceRoot(), request.projectRootAbs());
    }

    /** Resolves all additional source roots while enforcing workspace containment. */
    public Optional<List<Path>> resolveAdditionalRoots(
            DiscoverHandlersRequest request,
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

    private boolean isFullyQualifiedClassName(String value) {
        return value != null && value.indexOf('.') > 0 && !value.endsWith(".") && !value.contains(" ");
    }
}
