package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.infertarget;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisReportDetails;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import java.util.List;

/** Owns deterministic infer_target failure and report construction. */
public class InferTargetFailureReporter {

    /** Reports an invalid request type. */
    public RouteSynthesisResult invalidRequest() {
        return RouteSynthesisResult.report(
                "blocked_invalid", "invalid_request", "input_validation", "invalid_request",
                "Provide a valid infer_target request and rerun.");
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
                "fix_project_selector_input", "Provide an existing projectRootAbs contained by the workspace.");
    }

    /** Reports invalid or out-of-bound additional source roots. */
    public RouteSynthesisResult invalidAdditionalRoots() {
        return RouteSynthesisResult.report(
                "project_selector_invalid", "additional_source_roots_invalid",
                "additional_source_roots_validation", "fix_additional_source_roots",
                "Provide additionalSourceRoots contained by the workspace.");
    }

    /** Reports that source matching found no candidates. */
    public RouteSynthesisResult targetNotFound() {
        return RouteSynthesisResult.report(
                "target_not_found", "target_not_found", "target_inference", "refine_target_hints",
                "Refine classHint or methodHint and rerun infer_target.");
    }

    /** Reports that runtime evidence could not validate any candidate line. */
    public RouteSynthesisResult runtimeLineUnresolved(int candidateCount) {
        return RouteSynthesisResult.report(new RouteSynthesisReportDetails(
                "target_not_found", "runtime_line_unresolved", "line_validation", "select_resolvable_line",
                "Verify runtime/source alignment and rerun infer_target.",
                List.of("candidateCount=" + candidateCount),
                List.of("target_inference_exact_match", "runtime_line_validation")));
    }

    /** Reports that the requested line does not match validated candidates. */
    public RouteSynthesisResult lineHintNotResolvable() {
        return RouteSynthesisResult.report(
                "target_not_found", "line_hint_not_resolvable", "line_validation", "select_resolvable_line",
                "Use a validated line from class_methods and rerun infer_target.");
    }
}
