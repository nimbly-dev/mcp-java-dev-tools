package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.discoverhandlers;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.RouteSynthesisHandlerDiscoveryResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisReportDetails;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;

/** Owns deterministic discover_handlers failure and report construction. */
public class DiscoverHandlersFailureReporter {

    /** Reports an invalid request type. */
    public RouteSynthesisResult invalidRequest() {
        return RouteSynthesisResult.report(
                "blocked_invalid", "invalid_request", "input_validation", "invalid_request",
                "Provide a valid discover_handlers request and rerun.");
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
                "project_selector_invalid", "additional_source_roots_invalid",
                "additional_source_roots_validation", "fix_source_roots",
                "Provide additionalSourceRoots contained by the workspace.");
    }

    /** Maps a discovery failure into the public report envelope. */
    public RouteSynthesisResult discoveryFailure(RouteSynthesisHandlerDiscoveryResult discovered) {
        return RouteSynthesisResult.report(new RouteSynthesisReportDetails(
                discovered.status(), discovered.reasonCode(), discovered.failedStep(), discovered.nextAction(),
                "Resolve the reported handler discovery condition and rerun discover_handlers.",
                discovered.evidence(), discovered.attemptedStrategies()));
    }
}
