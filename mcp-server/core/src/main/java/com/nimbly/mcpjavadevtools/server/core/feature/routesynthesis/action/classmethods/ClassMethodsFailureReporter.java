package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.classmethods;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;

/** Owns deterministic class_methods failure construction. */
public class ClassMethodsFailureReporter {

    /** Reports an invalid request type. */
    public RouteSynthesisResult invalidRequest() {
        return RouteSynthesisResult.report(
                "blocked_invalid", "invalid_request", "input_validation", "invalid_request",
                "Provide a valid class_methods request and rerun.");
    }

    /** Reports that no workspace snapshot is bound. */
    public RouteSynthesisResult workspaceMissing() {
        return RouteSynthesisResult.report(
                "workspace_context_missing", "workspace_context_missing", "workspace_resolution",
                "bind_workspace_root", "Bind an MCP workspace root and rerun route_synthesis.");
    }

    /** Reports an invalid contained project root. */
    public RouteSynthesisResult invalidProjectRoot() {
        return RouteSynthesisResult.report(
                "project_selector_invalid", "project_selector_invalid", "project_root_validation",
                "fix_project_root", "Provide an existing projectRootAbs contained by the workspace.");
    }

    /** Reports invalid or out-of-bound additional source roots. */
    public RouteSynthesisResult invalidAdditionalRoots() {
        return RouteSynthesisResult.report(
                "project_selector_invalid", "project_selector_invalid", "additional_source_roots_validation",
                "fix_source_roots", "Provide additionalSourceRoots contained by the workspace.");
    }
}
