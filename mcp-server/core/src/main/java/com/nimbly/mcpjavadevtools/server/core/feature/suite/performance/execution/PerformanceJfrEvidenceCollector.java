package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.analysis.PerformanceAnalysisOptions;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.analysis.PerformanceStackSamples;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists bounded MSTA and sampled execution-correlation Artifacts from a downloaded JFR capture.
 */
final class PerformanceJfrEvidenceCollector {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    Evidence collect(
            Path jfrPath, Path runDirectory, List<String> strictLineKeys, String planId, String runId, Workload workload)
            throws IOException {
        requireReadableJfr(jfrPath);
        PerformanceJfrSamplingReader reader = new PerformanceJfrSamplingReader(workload.analysis(), runId);
        Map<List<String>, PerformanceStackSamples> samples = reader.read(jfrPath);
        Map<String, Object> msta = msta(jfrPath, strictLineKeys, samples, workload);
        msta = new LinkedHashMap<>(msta);
        msta.put("sourceEventTypes", reader.sourceTypes());
        msta.put("provider", Map.of("name", workload.effectiveProvider(), "event", workload.analysis().event(), "outputFormat", "jfr"));
        msta.put("timingBasis", "wall".equals(workload.analysis().event()) ? "sampled_wall_time" : "sampled_cpu_time");
        msta.put("timingScope", "aggregate_inclusive_thread_time");
        msta.put("sampling", reader.sampling());
        Path mstaPath = write(runDirectory.resolve("execution-timing.msta.json"), msta);
        if (!workload.analysis().correlationEnabled()) {
            return new Evidence(msta, disabledCorrelation(), mstaPath, null);
        }
        Map<String, Object> correlation = correlation(planId, runId, strictLineKeys, msta, mstaPath, workload);
        Path correlationPath = write(runDirectory.resolve("correlation").resolve("correlation.json"), correlation);
        return new Evidence(msta, correlation, mstaPath, correlationPath);
    }

    Evidence notConfigured(Path runDirectory) {
        return notConfigured(runDirectory, "not_configured");
    }

    Evidence notConfigured(Path runDirectory, String state) {
        return notConfigured(runDirectory, state, true);
    }

    Evidence notConfigured(Path runDirectory, String state, boolean correlationEnabled) {
        Map<String, Object> msta = Map.of("status", state);
        Map<String, Object> correlation = correlationEnabled
                ? Map.of("status", "unavailable", "reasonCode", "msta_" + state, "attributions", List.of()) : disabledCorrelation();
        return new Evidence(msta, correlation, null, null);
    }

    private static Map<String, Object> disabledCorrelation() {
        return Map.of("status", "disabled", "attributions", List.of());
    }

    private static void requireReadableJfr(Path jfrPath) throws IOException {
        if (!Files.isRegularFile(jfrPath) || Files.size(jfrPath) == 0) {
            throw new IOException("performance_jfr_missing");
        }
    }

    private static Map<String, Object> msta(
            Path jfrPath, List<String> strictLineKeys, Map<List<String>, PerformanceStackSamples> samples, Workload workload) {
        List<Map<String, Object>> methods = measuredMethods(strictLineKeys, samples, workload);
        List<Map<String, Object>> targets = methods.stream().filter(method -> method.containsKey("strictLineKey"))
                .map(PerformanceJfrEvidenceCollector::target).toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", 1);
        summary.put("unit", "ms");
        summary.put("jfrPath", jfrPath.toAbsolutePath().normalize().toString());
        summary.put("sourceEventTypes", List.of("jdk.ExecutionSample"));
        summary.put("durationMs", workload.durationSeconds() * 1_000L);
        summary.put("mode", workload.analysis().mode());
        summary.put("targets", targets);
        summary.put("methods", methods);
        List<String> missing = requestedAnchors(strictLineKeys, workload.analysis()).stream()
                .filter(anchor -> methods.stream().noneMatch(method -> anchor.equals(method.get("methodRef")))).toList();
        summary.put("missingMethodTargets", missing);
        String status = methods.isEmpty() ? "no_anchor_samples" : "available";
        if (!methods.isEmpty() && !missing.isEmpty()) {
            status = "partial_anchor_samples";
        }
        summary.put("status", status);
        return Map.copyOf(summary);
    }

    private static List<Map<String, Object>> measuredMethods(
            List<String> lineKeys, Map<List<String>, PerformanceStackSamples> samples, Workload workload) {
        Map<String, String> lines = new LinkedHashMap<>();
        lineKeys.forEach(key -> lines.putIfAbsent(anchor(key), key));
        List<String> requested = requestedAnchors(lineKeys, workload.analysis());
        List<Map<String, Object>> measured = new ArrayList<>();
        for (String method : requested) {
            Map<List<String>, PerformanceStackSamples> paths = anchorPaths(method, samples, workload.analysis());
            if (!paths.isEmpty()) {
                measured.add(measuredMethod(method, lines.get(method), paths, workload));
            }
        }
        return List.copyOf(measured);
    }

    private static List<String> requestedAnchors(List<String> lines, PerformanceAnalysisOptions options) {
        return options.methodTargets().isEmpty()
                ? lines.stream().map(PerformanceJfrEvidenceCollector::anchor).distinct().toList() : options.methodTargets();
    }

    private static Map<String, Object> target(Map<String, Object> method) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("strictLineKey", method.get("strictLineKey"));
        target.put("anchorMethod", method.get("methodRef"));
        target.put("anchoredSampleCount", method.get("samples"));
        target.put("dominantPathSampleCount", method.get("dominantPathSampleCount"));
        target.put("dominantPathSamplePct", method.get("estimatedTimePct"));
        target.put("dominantPathApproxTimeMs", method.get("dominantPathTimeMs"));
        target.put("steps", method.get("pathSteps"));
        return Map.copyOf(target);
    }

    private static Map<List<String>, PerformanceStackSamples> anchorPaths(String anchor, Map<List<String>, PerformanceStackSamples> samples,
            PerformanceAnalysisOptions options) {
        Map<List<String>, PerformanceStackSamples> paths = new LinkedHashMap<>();
        samples.forEach((frames, count) -> {
            int index = frames.indexOf(anchor);
            if (index >= 0) {
                List<String> path = frames.subList(0, index + 1).reversed().stream()
                        .filter(method -> allowed(method, anchor, options)).toList();
                paths.merge(path, count, PerformanceStackSamples::plus);
            }
        });
        return paths;
    }

    private static boolean allowed(String method, String anchor, PerformanceAnalysisOptions options) {
        if (method.equals(anchor)) {
            return true;
        }
        if (!options.includePackages().isEmpty()) {
            return options.includePackages().stream().anyMatch(prefix -> method.startsWith(prefix.replace("*", "")));
        }
        return options.allowThirdPartyFrames() || method.startsWith(anchor.substring(0, anchor.lastIndexOf('.') + 1));
    }

    private static Map<String, Object> measuredMethod(String anchor, String key, Map<List<String>, PerformanceStackSamples> paths,
            Workload workload) {
        int count = paths.values().stream().mapToInt(PerformanceStackSamples::count).sum();
        var dominant = paths.entrySet().stream().max(Comparator.<Map.Entry<List<String>, PerformanceStackSamples>>
                comparingDouble(entry -> entry.getValue().timeMs())
                .thenComparing(entry -> String.join(" -> ", entry.getKey()), Comparator.reverseOrder())).orElseThrow();
        Map<String, PerformanceStackSamples> methodCounts = new LinkedHashMap<>();
        paths.forEach((path, weight) -> path.stream().distinct()
                .forEach(method -> methodCounts.merge(method, weight, PerformanceStackSamples::plus)));
        List<String> selected = "method_targets".equals(workload.analysis().mode()) ? List.of(anchor) : dominant.getKey();
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            String method = selected.get(index);
            PerformanceStackSamples weight = methodCounts.get(method);
            steps.add(Map.of("stepOrder", index + 1, "methodRef", method, "target", method.equals(anchor), "samples", weight.count(),
                    "estimatedTimePct", weight.count() * 100.0 / count, "estimatedTimeMs", weight.timeMs()));
        }
        Map<String, Object> measured = new LinkedHashMap<>();
        measured.put("methodRef", anchor);
        measured.put("samples", count);
        measured.put("dominantPathSampleCount", dominant.getValue().count());
        measured.put("dominantPathTimeMs", dominant.getValue().timeMs());
        measured.put("estimatedTimeMs", paths.values().stream().mapToDouble(PerformanceStackSamples::timeMs).sum());
        measured.put("estimatedTimePct", dominant.getValue().count() * 100.0 / count);
        measured.put("pathSteps", steps);
        if (key != null) {
            measured.put("strictLineKey", key);
        }
        return Map.copyOf(measured);
    }

    private static String anchor(String strictLineKey) {
        int delimiter = strictLineKey.lastIndexOf(':');
        return delimiter > strictLineKey.indexOf('#') ? strictLineKey.substring(0, delimiter) : strictLineKey;
    }

    private static Path write(Path path, Map<String, Object> contents) throws IOException {
        Files.createDirectories(path.getParent());
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), contents);
        return path.toAbsolutePath().normalize();
    }

    private static Map<String, Object> correlation(
            String planId, String runId, List<String> strictLineKeys, Map<String, Object> msta, Path mstaPath,
            Workload workload) {
        List<Map<String, Object>> attributions = attributions(msta);
        Map<String, Object> correlation = new LinkedHashMap<>();
        correlation.put("schemaVersion", 1);
        correlation.put("suite", "performance");
        correlation.put("kind", "sampled_attribution");
        correlation.put("status", attributions.isEmpty() ? "unavailable" : "available");
        correlation.put("reasonCode", attributions.isEmpty() ? "msta_no_anchor_samples" : "sampled_attribution_available");
        correlation.put("run", Map.of("planId", planId, "runId", runId));
        correlation.put("workloadIdentity", workloadIdentity(workload));
        correlation.put("anchors", anchors(strictLineKeys));
        correlation.put("evidence", Map.of("lineHits", lineHits(strictLineKeys), "msta", Map.of(
                "status", msta.get("status"), "artifactRef", mstaPath.getFileName().toString())));
        correlation.put("attributions", attributions);
        return Map.copyOf(correlation);
    }

    private static Map<String, Object> workloadIdentity(Workload workload) {
        Map<String, Object> provider = workload.builtin() ? Map.of("type", "builtin") : Map.of("type", "jmeter", "mode", "generated_http");
        return Map.of("provider", provider, "entrypoint", Map.of(
                "protocol", "http", "method", workload.method(), "pathTemplate", workload.pathTemplate()),
                "loadModel", Map.of("mode", "concurrency", "concurrency", workload.concurrency(),
                        "rampUpSeconds", workload.rampUpSeconds(), "durationSeconds", workload.durationSeconds()));
    }

    private static List<Map<String, Object>> lineHits(List<String> strictLineKeys) {
        return strictLineKeys.stream().map(key -> Map.<String, Object>of("strictLineKey", key,
                "status", "verified_line_hit")).toList();
    }

    private static List<Map<String, Object>> anchors(List<String> strictLineKeys) {
        return strictLineKeys.stream().map(key -> Map.<String, Object>of("source", "verified_required_line_hit",
                "strictLineKey", key, "resolvedMethodRef", anchor(key), "lineHit", "verified_line_hit")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> attributions(Map<String, Object> msta) {
        Object value = msta.get("targets");
        if (!(value instanceof List<?> targets)) {
            return List.of();
        }
        List<Map<String, Object>> output = new ArrayList<>();
        for (Object candidate : targets) {
            if (candidate instanceof Map<?, ?> target) {
                Map<String, Object> typed = (Map<String, Object>) target;
                for (Map<String, Object> step : (List<Map<String, Object>>) typed.get("steps")) {
                    output.add(attribution(typed, step));
                }
            }
        }
        return output.stream().sorted(Comparator.comparing(valueMap -> (String) valueMap.get("strictLineKey"))).toList();
    }

    private static Map<String, Object> attribution(Map<String, Object> target, Map<String, Object> step) {
        String anchor = (String) target.get("anchorMethod");
        return Map.of("step", step.get("stepOrder"), "anchorMethod", anchor, "strictLineKey", target.get("strictLineKey"),
                "methodRef", step.get("methodRef"), "role", Boolean.TRUE.equals(step.get("target")) ? "anchor" : "dependency",
                "samples", step.get("samples"), "estimatedPathTimeMs", step.get("estimatedTimeMs"),
                "estimatedPathSharePct", step.get("estimatedTimePct"),
                "correlation", "correlated_sampled_path");
    }

    record Evidence(Map<String, Object> msta, Map<String, Object> correlation, Path mstaPath, Path correlationPath) {
    }

    record Workload(String method, String pathTemplate, int concurrency, int rampUpSeconds, int durationSeconds,
            boolean builtin, PerformanceAnalysisOptions analysis, String effectiveProvider) {
        Workload(String method, String pathTemplate, int concurrency, int rampUpSeconds, int durationSeconds) {
            this(method, pathTemplate, concurrency, rampUpSeconds, durationSeconds, false,
                    PerformanceAnalysisOptions.parse(OBJECT_MAPPER.createObjectNode()), "jfr");
        }
    }
}
