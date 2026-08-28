package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * Persists bounded MSTA and sampled execution-correlation Artifacts from a downloaded JFR capture.
 */
final class PerformanceJfrEvidenceCollector {

    private static final int MAX_SAMPLED_EVENTS = 100_000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    Evidence collect(
            Path jfrPath, Path runDirectory, List<String> strictLineKeys, String planId, String runId, Workload workload)
            throws IOException {
        requireReadableJfr(jfrPath);
        Map<String, Integer> samples = sampledMethods(jfrPath);
        Map<String, Object> msta = msta(jfrPath, strictLineKeys, samples, workload.durationSeconds());
        Path mstaPath = write(runDirectory.resolve("execution-timing.msta.json"), msta);
        Map<String, Object> correlation = correlation(planId, runId, strictLineKeys, msta, mstaPath, workload);
        Path correlationPath = write(runDirectory.resolve("correlation").resolve("correlation.json"), correlation);
        return new Evidence(msta, correlation, mstaPath, correlationPath);
    }

    Evidence notConfigured(Path runDirectory) {
        Map<String, Object> msta = Map.of("status", "not_configured");
        Map<String, Object> correlation = Map.of("status", "unavailable", "reasonCode", "msta_not_configured",
                "attributions", List.of());
        return new Evidence(msta, correlation, null, null);
    }

    private static void requireReadableJfr(Path jfrPath) throws IOException {
        if (!Files.isRegularFile(jfrPath) || Files.size(jfrPath) == 0) {
            throw new IOException("performance_jfr_missing");
        }
    }

    private static Map<String, Integer> sampledMethods(Path jfrPath) throws IOException {
        Map<String, Integer> samples = new LinkedHashMap<>();
        try (RecordingFile recording = new RecordingFile(jfrPath)) {
            int events = 0;
            while (recording.hasMoreEvents()) {
                if (++events > MAX_SAMPLED_EVENTS) {
                    throw new IOException("performance_jfr_sample_limit_exceeded");
                }
                recordSample(recording.readEvent(), samples);
            }
        }
        return samples;
    }

    private static void recordSample(RecordedEvent event, Map<String, Integer> samples) {
        if (!"jdk.ExecutionSample".equals(event.getEventType().getName())) {
            return;
        }
        RecordedStackTrace stack = event.getStackTrace();
        if (stack == null) {
            return;
        }
        for (RecordedFrame frame : stack.getFrames()) {
            RecordedMethod method = frame.getMethod();
            if (method != null) {
                samples.merge(method.getType().getName() + "#" + method.getName(), 1, Integer::sum);
            }
        }
    }

    private static Map<String, Object> msta(
            Path jfrPath, List<String> strictLineKeys, Map<String, Integer> samples, int durationSeconds) {
        List<Map<String, Object>> targets = targets(strictLineKeys, samples, durationSeconds);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", 1);
        summary.put("unit", "ms");
        summary.put("jfrPath", jfrPath.toAbsolutePath().normalize().toString());
        summary.put("sourceEventTypes", List.of("jdk.ExecutionSample"));
        summary.put("durationMs", durationSeconds * 1_000L);
        summary.put("mode", "required_line_hits");
        summary.put("targets", targets);
        summary.put("methods", methods(targets));
        summary.put("status", targets.isEmpty() ? "no_anchor_samples" : "available");
        return Map.copyOf(summary);
    }

    private static List<Map<String, Object>> targets(
            List<String> strictLineKeys, Map<String, Integer> samples, int durationSeconds) {
        List<Map<String, Object>> targets = new ArrayList<>();
        for (String strictLineKey : strictLineKeys) {
            String anchor = anchor(strictLineKey);
            int count = samples.getOrDefault(anchor, 0);
            if (count > 0) {
                targets.add(target(strictLineKey, anchor, count, durationSeconds));
            }
        }
        return List.copyOf(targets);
    }

    private static Map<String, Object> target(String strictLineKey, String anchor, int count, int durationSeconds) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("strictLineKey", strictLineKey);
        target.put("anchorMethod", anchor);
        target.put("anchoredSampleCount", count);
        target.put("dominantPathSampleCount", count);
        target.put("dominantPathSamplePct", 100.0);
        target.put("dominantPathApproxTimeMs", durationSeconds * 1_000.0);
        target.put("steps", List.of(Map.of("stepOrder", 1, "methodRef", anchor, "target", true,
                "samples", count, "estimatedTimePct", 100.0, "estimatedTimeMs", durationSeconds * 1_000.0)));
        return Map.copyOf(target);
    }

    private static List<Map<String, Object>> methods(List<Map<String, Object>> targets) {
        return targets.stream().map(target -> Map.of("methodRef", target.get("anchorMethod"),
                "strictLineKey", target.get("strictLineKey"), "samples", target.get("anchoredSampleCount"),
                "estimatedTimeMs", target.get("dominantPathApproxTimeMs"), "estimatedTimePct", 100.0,
                "pathSteps", target.get("steps"))).toList();
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
        return Map.of("provider", Map.of("type", "jmeter", "mode", "generated_http"), "entrypoint", Map.of(
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
                output.add(attribution((Map<String, Object>) target));
            }
        }
        return output.stream().sorted(Comparator.comparing(valueMap -> (String) valueMap.get("strictLineKey"))).toList();
    }

    private static Map<String, Object> attribution(Map<String, Object> target) {
        String anchor = (String) target.get("anchorMethod");
        return Map.of("step", 1, "anchorMethod", anchor, "strictLineKey", target.get("strictLineKey"),
                "methodRef", anchor, "role", "anchor", "samples", target.get("anchoredSampleCount"),
                "estimatedPathTimeMs", target.get("dominantPathApproxTimeMs"), "estimatedPathSharePct", 100.0,
                "correlation", "correlated_sampled_path");
    }

    record Evidence(Map<String, Object> msta, Map<String, Object> correlation, Path mstaPath, Path correlationPath) {
    }

    record Workload(String method, String pathTemplate, int concurrency, int rampUpSeconds, int durationSeconds) {
    }
}
