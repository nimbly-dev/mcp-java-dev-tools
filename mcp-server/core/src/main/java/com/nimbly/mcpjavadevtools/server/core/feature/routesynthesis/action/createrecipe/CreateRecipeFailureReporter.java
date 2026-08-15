package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.createrecipe;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.RouteSynthesisHandlerDiscoveryResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.SynthesizerSelection;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisReportDetails;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeMappingResolution;
import java.util.List;

/** Produces the stable create_recipe report envelopes. */
public class CreateRecipeFailureReporter {

    public RouteSynthesisResult externalPluginBlocker(SynthesizerSelection selection) {
        return RouteSynthesisResult.report(new RouteSynthesisReportDetails(
                "report", "synthesizer_not_installed", "plugin_bootstrap",
                "configure_java_synthesizer_or_use_typescript",
                "Use the TypeScript Compatibility Implementation when JavaScript Synthesizer plugins are configured.",
                List.of("configuredModuleCount=" + selection.configuredModuleCount()),
                List.of("registry_plugin_bootstrap")));
    }

    public RouteSynthesisResult noCompatibleSynthesizer(SynthesizerSelection selection) {
        return RouteSynthesisResult.report(new RouteSynthesisReportDetails(
                "report", selection.reasonCode(), "plugin_selection", "synthesizer_not_installed",
                "Install a compatible Synthesizer and rerun create_recipe.", List.of(),
                List.of("registry_plugin_selection")));
    }

    public RouteSynthesisResult targetDiscoveryFailure(RouteSynthesisHandlerDiscoveryResult discovery) {
        String reason = "target_type_not_found".equals(discovery.reasonCode())
                || "target_type_ambiguous".equals(discovery.reasonCode())
                ? "target_candidate_missing" : discovery.reasonCode();
        String step = reason.equals("target_candidate_missing") ? "target_inference" : discovery.failedStep();
        return RouteSynthesisResult.report(
                "target_not_inferred", reason, step, "refine_target_hints",
                "Refine classHint or methodHint and rerun create_recipe.");
    }

    public RouteSynthesisResult targetMissing() {
        return RouteSynthesisResult.report(
                "target_not_inferred", "target_candidate_missing", "target_inference", "refine_target_hints",
                "Refine classHint or methodHint and rerun create_recipe.");
    }

    public RouteSynthesisResult runtimeLineUnresolved() {
        return RouteSynthesisResult.report(
                "target_not_inferred", "runtime_line_unresolved", "line_validation", "select_resolvable_line",
                "Provide a reachable Probe route and rerun create_recipe.");
    }

    public RouteSynthesisResult workspaceMissing() {
        return RouteSynthesisResult.report(
                "workspace_context_missing", "workspace_context_missing", "workspace_resolution",
                "bind_workspace_root", "Bind an MCP workspace root and rerun create_recipe.");
    }

    public RouteSynthesisResult invalidAdditionalRoots() {
        return RouteSynthesisResult.report(
                "project_selector_invalid", "additional_source_roots_invalid",
                "additional_source_roots_validation", "fix_source_roots",
                "Provide additionalSourceRoots contained by the workspace.");
    }

    public RouteSynthesisResult invalidRequest() {
        return RouteSynthesisResult.report(
                "blocked_invalid", "invalid_request", "input_validation", "invalid_request",
                "Provide a valid create_recipe request and rerun.");
    }

    public RouteSynthesisResult runtimeFailure(
            RouteSynthesisRuntimeMappingResolution runtime, String nextAction) {
        return RouteSynthesisResult.report(new RouteSynthesisReportDetails(
                "report", runtime.reasonCode(), runtime.failedStep(), runtime.nextAction(),
                nextAction, runtime.evidence(), runtime.attemptedStrategies()));
    }
}
