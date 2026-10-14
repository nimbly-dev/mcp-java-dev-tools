package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerProvider;
import java.util.List;
import java.util.stream.StreamSupport;

/** Immutable retained profiler/MSTA policy parsed without I/O or runtime decisions. */
public record PerformanceAnalysisOptions(String reasonCode, boolean profilerEnabled, String mstaState,
        ProbeProfilerProvider provider, String event, Long intervalNanos, String mode, List<String> methodTargets,
        List<String> includePackages, boolean allowThirdPartyFrames, boolean correlationEnabled, boolean requireMsta) {
    public PerformanceAnalysisOptions {
        methodTargets = List.copyOf(methodTargets);
        includePackages = List.copyOf(includePackages);
    }

    public static PerformanceAnalysisOptions parse(JsonNode analysis) {
        JsonNode timing = analysis.path("executionTiming");
        if (timing.isMissingNode()) {
            timing = analysis.path("profiler");
        }
        JsonNode msta = analysis.path("msta");
        JsonNode correlation = analysis.path("correlation");
        boolean profilerEnabled = timing.path("enabled").asBoolean(false);
        String state = msta.isMissingNode() ? "not_configured" : "disabled";
        if (msta.path("enabled").asBoolean(false)) {
            state = "available";
        }
        String provider = timing.path("provider").asText("jfr");
        String mode = msta.path("mode").asText("required_line_hits");
        List<String> targets = targets(msta.path("methodTargets"));
        if (msta.path("mode").isMissingNode() && !targets.isEmpty()) {
            mode = "method_targets";
        }
        String reason = validate(timing, msta, correlation, provider);
        if (reason == null && state.equals("available") && !profilerEnabled) {
            reason = "performance_msta_profiler_required";
        }
        ProbeProfilerProvider intent = switch (provider) {
            case "auto" -> ProbeProfilerProvider.AUTO;
            case "async-profiler" -> ProbeProfilerProvider.ASYNC_PROFILER;
            default -> ProbeProfilerProvider.JFR;
        };
        return new PerformanceAnalysisOptions(reason, profilerEnabled, state, intent,
                timing.path("event").asText(state.equals("available") ? "wall" : "cpu"), timing.path("intervalNanos").isMissingNode()
                        ? null : timing.path("intervalNanos").asLong(), mode, targets, strings(msta.path("includePackages")),
                msta.path("allowThirdPartyFrames").asBoolean(false), correlation.isMissingNode() || correlation.path("enabled").asBoolean(false),
                correlation.path("enabled").asBoolean(false) && correlation.path("requireMsta").asBoolean(false));
    }

    private static String validate(JsonNode timing, JsonNode msta, JsonNode correlation, String provider) {
        if (invalidToggle(timing) || invalidToggle(msta) || invalidToggle(correlation)) {
            return "performance_plan_input_invalid";
        }
        if (timing.path("enabled").asBoolean(false) && (!List.of("auto", "jfr", "async-profiler").contains(provider)
                || (!timing.path("outputFormat").isMissingNode() && !"jfr".equals(timing.path("outputFormat").asText())))) {
            return "performance_plan_input_invalid";
        }
        JsonNode interval = timing.path("intervalNanos");
        if (!interval.isMissingNode() && (!interval.isIntegralNumber() || !interval.canConvertToLong() || interval.asLong() <= 0)) {
            return "performance_plan_input_invalid";
        }
        if (msta.path("enabled").asBoolean(false) && !validMsta(msta)) {
            return "performance_plan_input_invalid";
        }
        if (correlation.path("enabled").asBoolean(false) && !validCorrelation(correlation)) {
            return "performance_plan_input_invalid";
        }
        return null;
    }

    private static boolean invalidToggle(JsonNode node) {
        return !node.isMissingNode() && (!node.isObject() || !node.path("enabled").isBoolean());
    }

    private static boolean validMsta(JsonNode msta) {
        JsonNode mode = msta.path("mode");
        JsonNode targets = msta.path("methodTargets");
        JsonNode packages = msta.path("includePackages");
        JsonNode thirdParty = msta.path("allowThirdPartyFrames");
        JsonNode selection = msta.path("anchorSelection");
        return (mode.isMissingNode() || List.of("method_targets", "target_plus_path").contains(mode.asText()))
                && (targets.isMissingNode() || (targets.isArray() && !targets.isEmpty()
                        && targets(targets).size() == targets.size() && targets(targets).stream().allMatch(value -> value.contains("#"))))
                && (packages.isMissingNode() || (packages.isArray() && strings(packages).size() == packages.size()))
                && (thirdParty.isMissingNode() || thirdParty.isBoolean())
                && (selection.isMissingNode() || ("verified_required_line_hits".equals(selection.path("source").asText())
                        && "all".equals(selection.path("strategy").asText())));
    }

    private static boolean validCorrelation(JsonNode correlation) {
        return "sampled_attribution".equals(correlation.path("kind").asText())
                && "msta_resolved_anchors".equals(correlation.path("anchorSource").asText())
                && correlation.path("requireLineHit").isBoolean() && correlation.path("requireMsta").isBoolean();
    }

    private static List<String> targets(JsonNode targets) {
        return StreamSupport.stream(targets.spliterator(), false)
                .map(target -> target.isTextual() ? target : target.path("methodRef"))
                .filter(JsonNode::isTextual).map(JsonNode::asText).map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
    }

    private static List<String> strings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).filter(JsonNode::isTextual)
                .map(JsonNode::asText).map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
    }
}
