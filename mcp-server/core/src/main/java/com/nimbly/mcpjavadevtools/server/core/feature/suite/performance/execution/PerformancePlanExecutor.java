package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.result.PerformanceSuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.health.PerformanceTargetHealthCheck;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterExecutableResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterWorkloadExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterWorkloadRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterWorkloadResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeBatchResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitForHitRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitForHitResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitOutcome;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerCommand;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeyBatchSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeySelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Validates and executes the generated-HTTP JMeter Performance plan contract. */
public final class PerformancePlanExecutor {

    private static final int MAX_CONCURRENCY = 10_000;
    private static final int MAX_DURATION_SECONDS = 1_800;
    private final JmeterExecutableResolver executableResolver;
    private final JmeterWorkloadExecutor workloadExecutor;
    private final ProbeFeature probe;
    private final PerformanceTargetHealthCheck healthCheck;
    private final PerformanceJfrEvidenceCollector jfrEvidenceCollector;

    /** Creates the executor with JMeter workload collaborators. */
    public PerformancePlanExecutor(JmeterExecutableResolver executableResolver, JmeterWorkloadExecutor workloadExecutor) {
        this(executableResolver, workloadExecutor, null, null);
    }

    /** Creates the executor with the required public Probe Core Feature. */
    public PerformancePlanExecutor(
            JmeterExecutableResolver executableResolver,
            JmeterWorkloadExecutor workloadExecutor,
            ProbeFeature probe) {
        this(executableResolver, workloadExecutor, probe, null);
    }

    /** Creates the executor with the required Probe and wrapped target-health collaborators. */
    public PerformancePlanExecutor(
            JmeterExecutableResolver executableResolver,
            JmeterWorkloadExecutor workloadExecutor,
            ProbeFeature probe,
            TransportExecutionFeature transport) {
        this.executableResolver = Objects.requireNonNull(executableResolver, "executableResolver must not be null");
        this.workloadExecutor = Objects.requireNonNull(workloadExecutor, "workloadExecutor must not be null");
        this.probe = probe;
        this.healthCheck = transport == null ? null : new PerformanceTargetHealthCheck(transport);
        this.jfrEvidenceCollector = new PerformanceJfrEvidenceCollector();
    }

    /** Executes a plan or returns a deterministic preflight failure. */
    public PerformanceSuiteResult execute(JsonNode input) {
        Validation validation = validate(input);
        if (validation.reasonCode() != null) {
            return blocked(validation.reasonCode(), validation.nextAction());
        }
        return executableResolver.resolve(validation.installationPath())
                .map(executable -> run(validation, executable))
                .orElseGet(() -> blocked("performance_jmeter_missing",
                        "install Apache JMeter, set MCP_JAVA_DEV_TOOLS_JMETER_HOME, or add JMeter to PATH"));
    }

    private PerformanceSuiteResult run(Validation validation, String executable) {
        PerformanceSuiteResult health = health(validation);
        if (health != null) {
            return health;
        }
        PerformanceSuiteResult reset = reset(validation);
        if (reset != null) {
            return reset;
        }
        ProfilerEvidence profiler = startProfiler(validation);
        if (profiler.blocked()) {
            return blocked(profiler.reasonCode(), "restore Sidecar profiler availability before running the workload");
        }
        JmeterWorkloadResult result = workloadExecutor.execute(new JmeterWorkloadRequest(
                validation.runDirectory(), validation.planName(), executable, validation.method(), validation.url(),
                validation.headers(), validation.body(), validation.timeoutMs(), validation.concurrency(),
                validation.rampUpSeconds(), validation.durationSeconds()));
        if (!result.completed()) {
            stopProfiler(validation, profiler);
            return blocked(result.reasonCode(), "inspect generated JMeter Artifacts and rerun the Performance plan");
        }
        List<String> lineHits = verifyLineHits(validation);
        ProfilerEvidence stopped = stopProfiler(validation, profiler);
        if (stopped.blocked()) {
            return blocked(stopped.reasonCode(), "stop the Sidecar profiler and inspect its bounded lifecycle evidence");
        }
        if (lineHits == null) {
            return blocked("performance_required_line_hit_missing", "verify every required Strict Line Key and rerun");
        }
        ProfilerEvidence captured = downloadProfiler(validation, stopped);
        if (captured.blocked()) {
            return blocked(captured.reasonCode(), "download the bounded JFR capture before completing the Performance run");
        }
        PerformanceJfrEvidenceCollector.Evidence evidence = collectEvidence(validation, captured);
        if (evidence == null) {
            return blocked("performance_msta_no_anchor_samples",
                    "collect JFR samples for every required Strict Line Key before completing the Performance run");
        }
        return completed(result, lineHits, validation.thresholds(), validation.durationSeconds(), captured, evidence);
    }

    private ProfilerEvidence startProfiler(Validation validation) {
        if (!validation.profilerEnabled()) {
            return ProfilerEvidence.notConfigured();
        }
        if (probe == null) {
            return ProfilerEvidence.blocked("performance_profiler_unavailable");
        }
        var result = probe.execute(new ProbeProfilerRequest(target(validation), ProbeProfilerCommand.START,
                validation.profilerSessionId(), null, "jdk.ExecutionSample", null, null, "jfr", Duration.ofSeconds(15)));
        return profilerEvidence(result, "running", validation.capturePath(), "performance_profiler_start_failed");
    }

    private ProfilerEvidence stopProfiler(Validation validation, ProfilerEvidence profiler) {
        if (!profiler.started()) {
            return profiler;
        }
        var result = probe.execute(new ProbeProfilerRequest(target(validation), ProbeProfilerCommand.STOP,
                validation.profilerSessionId(), null, null, null, null, "jfr", Duration.ofSeconds(15)));
        return profilerEvidence(result, "stopped", profiler.capturePath(), "performance_profiler_stop_failed");
    }

    private ProfilerEvidence downloadProfiler(Validation validation, ProfilerEvidence profiler) {
        if (!profiler.started()) {
            return profiler;
        }
        var response = probe.execute(new ProbeProfilerRequest(target(validation), ProbeProfilerCommand.DOWNLOAD,
                validation.profilerSessionId(), null, null, null, profiler.capturePath().toString(), "jfr", Duration.ofSeconds(15)));
        return downloadEvidence(response, profiler);
    }

    private static ProfilerEvidence profilerEvidence(
            com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult response,
            String expectedStatus,
            Path capturePath,
            String failureCode) {
        return response.actionResult().filter(ProbeProfilerResult.class::isInstance)
                .map(ProbeProfilerResult.class::cast)
                .filter(result -> response.status().name().equals("SUCCESS") && expectedStatus.equals(result.status()))
                .map(result -> new ProfilerEvidence(false, null, true, result.sessionId(), result.provider(),
                        result.outputPath(), result.outputFormat(), capturePath))
                .orElseGet(() -> ProfilerEvidence.blocked(failureCode));
    }

    private static ProfilerEvidence downloadEvidence(
            com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult response,
            ProfilerEvidence profiler) {
        return response.actionResult().filter(ProbeProfilerResult.class::isInstance)
                .map(ProbeProfilerResult.class::cast)
                .filter(result -> response.status().name().equals("SUCCESS") && "downloaded".equals(result.status()))
                .filter(result -> result.downloadedBytes() != null && result.downloadedBytes() > 0)
                .map(result -> profiler.downloaded(result.outputPath()))
                .orElseGet(() -> ProfilerEvidence.blocked("performance_profiler_download_failed"));
    }

    private PerformanceJfrEvidenceCollector.Evidence collectEvidence(Validation validation, ProfilerEvidence profiler) {
        if (!profiler.started()) {
            return jfrEvidenceCollector.notConfigured(validation.runDirectory());
        }
        try {
            return jfrEvidenceCollector.collect(profiler.capturePath(), validation.runDirectory(),
                    validation.requiredLineHits(), validation.planName(), validation.profilerSessionId(),
                    workload(validation));
        } catch (java.io.IOException exception) {
            return null;
        }
    }

    private static PerformanceJfrEvidenceCollector.Workload workload(Validation validation) {
        return new PerformanceJfrEvidenceCollector.Workload(
                validation.method(), pathTemplate(validation.url()), validation.concurrency(),
                validation.rampUpSeconds(), validation.durationSeconds());
    }

    private static String pathTemplate(String url) {
        try {
            String path = URI.create(url).getRawPath();
            return path == null || path.isBlank() ? "/" : path;
        } catch (IllegalArgumentException exception) {
            return "/";
        }
    }

    private PerformanceSuiteResult health(Validation validation) {
        if (validation.healthCheckUrl() == null) {
            return null;
        }
        if (healthCheck == null) {
            return blocked("performance_healthcheck_unavailable", "configure wrapped Transport execution before running the health check");
        }
        PerformanceTargetHealthCheck.Result result = healthCheck.verify(validation.healthCheckUrl(), validation.timeoutMs());
        return result.ready() ? null : blocked(result.reasonCode(), "restore target readiness before executing the Performance workload");
    }

    private PerformanceSuiteResult reset(Validation validation) {
        if (probe == null) {
            return blocked("performance_probe_unavailable", "configure a Probe before executing the Performance workload");
        }
        var response = probe.execute(new ProbeBatchResetRequest(target(validation),
                new ProbeKeyBatchSelector(validation.requiredLineHits()), Duration.ofSeconds(10)));
        return response.status().name().equals("SUCCESS") ? null
                : blocked("performance_probe_reset_failed", "restore Probe connectivity and reset required Strict Line Keys");
    }

    private List<String> verifyLineHits(Validation validation) {
        List<String> verified = new ArrayList<>();
        for (String key : validation.requiredLineHits()) {
            if (!lineHit(validation, key)) {
                return null;
            }
            verified.add(key);
        }
        return List.copyOf(verified);
    }

    private boolean lineHit(Validation validation, String key) {
        var response = probe.execute(new ProbeWaitForHitRequest(target(validation), new ProbeKeySelector(key, null),
                Duration.ofSeconds(validation.durationSeconds()), Duration.ofMillis(250), null));
        return response.actionResult().filter(ProbeWaitForHitResult.class::isInstance)
                .map(ProbeWaitForHitResult.class::cast)
                .map(ProbeWaitForHitResult::outcome)
                .filter(ProbeWaitOutcome.LINE_HIT::equals)
                .isPresent();
    }

    private static ProbeTargetSelector target(Validation validation) {
        return new ProbeTargetSelector(validation.probeId(), validation.probeBaseUrl());
    }

    private PerformanceSuiteResult completed(
            JmeterWorkloadResult result,
            List<String> lineHits,
            Thresholds thresholds,
            int durationSeconds,
            ProfilerEvidence profiler,
            PerformanceJfrEvidenceCollector.Evidence evidence) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalRequests", result.totalRequests());
        metrics.put("failedRequests", result.failedRequests());
        metrics.put("latenciesMs", result.latenciesMs());
        metrics.put("errorRatePct", errorRatePct(result));
        metrics.put("throughputPerSec", throughput(result, durationSeconds));
        metrics.put("p95LatencyMs", percentile(result.latenciesMs(), 0.95));
        Map<String, Object> thresholdResults = thresholdResults(result, thresholds, durationSeconds);
        Map<String, Object> artifacts = Map.of(
                "jmxPath", result.jmxPath().toString(),
                "jtlPath", result.jtlPath().toString(),
                "logPath", result.logPath().toString());
        return PerformanceSuiteResult.completed(Map.of(
                "runStatus", thresholdStatus(thresholdResults),
                "metrics", metrics,
                "thresholdResults", thresholdResults,
                "requiredLineHits", lineHits,
                "profiler", profiler.details(),
                "msta", evidence.msta(),
                "correlation", evidence.correlation(),
                "artifacts", artifactPaths(artifacts, evidence)));
    }

    private static Map<String, Object> artifactPaths(
            Map<String, Object> workloadArtifacts, PerformanceJfrEvidenceCollector.Evidence evidence) {
        Map<String, Object> artifacts = new LinkedHashMap<>(workloadArtifacts);
        if (evidence.mstaPath() != null) {
            artifacts.put("mstaPath", evidence.mstaPath().toString());
        }
        if (evidence.correlationPath() != null) {
            artifacts.put("correlationPath", evidence.correlationPath().toString());
        }
        Object jfrPath = evidence.msta().get("jfrPath");
        if (jfrPath != null) {
            artifacts.put("jfrPath", jfrPath);
        }
        return Map.copyOf(artifacts);
    }

    private Validation validate(JsonNode input) {
        if (input == null || !input.isObject()) {
            return Validation.invalid("performance_plan_input_invalid", "provide a Performance plan object");
        }
        JsonNode contract = contract(input);
        String installationPath = text(contract.path("workloadProvider").path("options").path("installationPath"));
        String url = requestUrl(input, contract);
        String runDirectory = text(input.path("runDirectory"));
        int concurrency = positive(contract.path("loadModel").path("concurrency"));
        int durationSeconds = positive(contract.path("loadModel").path("durationSeconds"));
        ObservationTargets observationTargets = observationTargets(input, contract);
        Validation required = validateRequired(contract, url, runDirectory, concurrency, durationSeconds);
        if (required != null) {
            return required;
        }
        if (observationTargets.requiredLineHits().isEmpty()
                || (observationTargets.probeId() == null && observationTargets.probeBaseUrl() == null)) {
            return Validation.invalid("performance_required_line_hit_missing", "provide requiredLineHits and a Probe selector");
        }
        if (concurrency > MAX_CONCURRENCY || durationSeconds > MAX_DURATION_SECONDS) {
            return Validation.invalid("performance_plan_bounds_invalid", "reduce concurrency or duration to the supported hard ceiling");
        }
        Thresholds thresholds = thresholds(contract.path("successCriteria"));
        if (thresholds == null) {
            return Validation.invalid("performance_threshold_invalid", "provide deterministic successCriteria thresholds");
        }
        String planName = textOr(input.path("planName"), "performance-plan");
        RequestDetails request = requestDetails(input, contract);
        int rampUpSeconds = nonNegative(contract.path("loadModel").path("rampUpSeconds"));
        if (rampUpSeconds < 0) {
            return Validation.invalid("performance_plan_input_invalid", "provide a non-negative rampUpSeconds");
        }
        Analysis analysis = analysis(input, contract, planName);
        if (analysis.reasonCode() != null) {
            return Validation.invalid(analysis.reasonCode(), analysis.nextAction());
        }
        return new Validation(
                null, null, installationPath, Path.of(runDirectory), planName, request.method(), url, request.headers(), request.body(),
                request.timeoutMs(), concurrency, rampUpSeconds, durationSeconds, observationTargets.requiredLineHits(),
                observationTargets.probeId(), observationTargets.probeBaseUrl(), request.healthCheckUrl(), thresholds,
                analysis.profilerEnabled(), analysis.mstaEnabled(), analysis.profilerSessionId(),
                Path.of(runDirectory).resolve("execution-timing.jfr").toAbsolutePath().normalize());
    }

    private static RequestDetails requestDetails(JsonNode input, JsonNode contract) {
        JsonNode entrypoint = contract.path("entrypoints").path(0);
        return new RequestDetails(
                textOr(entrypoint.path("request").path("method"), "GET"),
                headers(entrypoint.path("request").path("headers")),
                text(entrypoint.path("request").path("body")),
                positiveOr(input.path("request").path("timeoutMs"), 30_000),
                healthCheckUrl(entrypoint));
    }

    private static Analysis analysis(JsonNode input, JsonNode contract, String planName) {
        JsonNode analysis = contract.path("analysis");
        boolean profilerEnabled = analysis.path("executionTiming").path("enabled").asBoolean(false)
                || analysis.path("profiler").path("enabled").asBoolean(false);
        boolean mstaEnabled = contract.path("analysis").path("msta").path("enabled").asBoolean(false);
        if (mstaEnabled && !profilerEnabled) {
            return new Analysis("performance_msta_profiler_required",
                    "enable JFR profiling before requesting MSTA", false, false, null);
        }
        return new Analysis(null, null, profilerEnabled, mstaEnabled,
                textOr(input.path("suiteRunId"), planName + "-jfr"));
    }

    private static Validation validateRequired(
            JsonNode contract,
            String url,
            String runDirectory,
            int concurrency,
            int durationSeconds) {
        boolean fieldsMissing = url == null || runDirectory == null;
        boolean loadModelInvalid = concurrency == 0 || durationSeconds == 0;
        boolean providerInvalid = !"jmeter".equals(text(contract.path("workloadProvider").path("type")))
                || !"generated_http".equals(text(contract.path("workloadProvider").path("mode")));
        return fieldsMissing || loadModelInvalid || providerInvalid
                ? Validation.invalid("performance_plan_input_invalid",
                        "provide request.url, runDirectory, concurrency, and durationSeconds")
                : null;
    }

    private static JsonNode contract(JsonNode input) {
        return input.path("contract").isObject() ? input.path("contract") : input;
    }

    private static String requestUrl(JsonNode input, JsonNode contract) {
        String resolved = url(contract.path("entrypoints"));
        return resolved == null ? text(input.path("request").path("url")) : resolved;
    }

    private static String url(JsonNode entrypoints) {
        JsonNode entrypoint = entrypoints.path(0);
        String baseUrl = text(entrypoint.path("transport").path("baseUrl"));
        String path = text(entrypoint.path("request").path("path"));
        if (baseUrl == null || path == null) {
            return null;
        }
        try {
            return URI.create(baseUrl).resolve(path).toString();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String healthCheckUrl(JsonNode entrypoint) {
        String baseUrl = text(entrypoint.path("transport").path("baseUrl"));
        String healthCheckPath = text(entrypoint.path("transport").path("healthCheckPath"));
        if (baseUrl == null || healthCheckPath == null) {
            return null;
        }
        try {
            return URI.create(baseUrl).resolve(healthCheckPath).toString();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static ObservationTargets observationTargets(JsonNode input, JsonNode contract) {
        JsonNode targets = contract.path("observationTargets");
        String probeBaseUrl = text(input.path("probeBaseUrl"));
        if (probeBaseUrl == null) {
            probeBaseUrl = text(targets.path("probeBaseUrl"));
        }
        return new ObservationTargets(lines(targets.path("requiredLineHits")), text(targets.path("probeId")), probeBaseUrl);
    }

    private static Thresholds thresholds(JsonNode node) {
        double maxErrorRatePct = node.path("maxErrorRatePct").asDouble(-1);
        double minThroughput = node.path("minThroughputPerSec").asDouble(-1);
        double p95Latency = node.path("p95LatencyMs").asDouble(-1);
        if (maxErrorRatePct < 0 || minThroughput <= 0 || p95Latency <= 0) {
            return null;
        }
        return new Thresholds(maxErrorRatePct, minThroughput, p95Latency);
    }

    private static double errorRatePct(JmeterWorkloadResult result) {
        return result.totalRequests() == 0 ? 100.0 : result.failedRequests() * 100.0 / result.totalRequests();
    }

    private static double throughput(JmeterWorkloadResult result, int durationSeconds) {
        return result.totalRequests() / (double) durationSeconds;
    }

    private static double percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index);
    }

    private static Map<String, Object> thresholdResults(
            JmeterWorkloadResult result,
            Thresholds thresholds,
            int durationSeconds) {
        double errorRatePct = errorRatePct(result);
        double throughput = throughput(result, durationSeconds);
        double p95Latency = percentile(result.latenciesMs(), 0.95);
        return Map.of(
                "maxErrorRatePct", threshold(errorRatePct, thresholds.maxErrorRatePct(), errorRatePct <= thresholds.maxErrorRatePct()),
                "minThroughputPerSec", threshold(throughput, thresholds.minThroughputPerSec(), throughput >= thresholds.minThroughputPerSec()),
                "p95LatencyMs", threshold(p95Latency, thresholds.p95LatencyMs(), p95Latency <= thresholds.p95LatencyMs()));
    }

    private static Map<String, Object> threshold(double actual, double limit, boolean pass) {
        return Map.of("actual", actual, "limit", limit, "pass", pass);
    }

    private static String thresholdStatus(Map<String, Object> thresholdResults) {
        return thresholdResults.values().stream().allMatch(value -> ((Map<?, ?>) value).get("pass").equals(true))
                ? "pass" : "fail";
    }

    private static Map<String, String> headers(JsonNode node) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
        }
        return Map.copyOf(headers);
    }

    private static List<String> lines(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            String line = text(value);
            if (line != null) {
                values.add(line);
            }
        });
        return List.copyOf(values);
    }

    private static PerformanceSuiteResult blocked(String reasonCode, String nextAction) {
        return PerformanceSuiteResult.blocked(reasonCode, nextAction, Map.of("failedStep", "performance_preflight"));
    }

    private static int positive(JsonNode node) {
        return node.canConvertToInt() && node.asInt() > 0 ? node.asInt() : 0;
    }

    private static int positiveOr(JsonNode node, int fallback) {
        return positive(node) == 0 ? fallback : positive(node);
    }

    private static int nonNegative(JsonNode node) {
        return node.canConvertToInt() && node.asInt() >= 0 ? node.asInt() : -1;
    }

    private static String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText().trim() : null;
    }

    private static String textOr(JsonNode node, String fallback) {
        String value = text(node);
        return value == null ? fallback : value;
    }

    private record Validation(String reasonCode, String nextAction, String installationPath, Path runDirectory, String planName,
            String method, String url, Map<String, String> headers, String body, int timeoutMs, int concurrency,
            int rampUpSeconds, int durationSeconds, List<String> requiredLineHits, String probeId, String probeBaseUrl,
            String healthCheckUrl, Thresholds thresholds, boolean profilerEnabled, boolean mstaEnabled,
            String profilerSessionId, Path capturePath) {
        private static Validation invalid(String reasonCode, String nextAction) {
            return new Validation(reasonCode, nextAction, null, null, null, null, null, Map.of(), null, 0, 0, 0, 0,
                    List.of(), null, null, null, null, false, false, null, null);
        }
    }

    private record ProfilerEvidence(boolean blocked, String reasonCode, boolean started, String sessionId,
            String provider, String outputPath, String outputFormat, Path capturePath) {
        private static ProfilerEvidence notConfigured() {
            return new ProfilerEvidence(false, null, false, null, null, null, null, null);
        }

        private static ProfilerEvidence blocked(String reasonCode) {
            return new ProfilerEvidence(true, reasonCode, false, null, null, null, null, null);
        }

        private ProfilerEvidence downloaded(String downloadedPath) {
            return new ProfilerEvidence(false, null, true, sessionId, provider, downloadedPath, outputFormat, capturePath);
        }

        private Map<String, Object> details() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("status", started ? "stopped" : "not_configured");
            if (provider != null) {
                details.put("provider", provider);
            }
            if (outputPath != null) {
                details.put("outputPath", outputPath);
            }
            if (outputFormat != null) {
                details.put("outputFormat", outputFormat);
            }
            return Map.copyOf(details);
        }
    }

    private record Thresholds(double maxErrorRatePct, double minThroughputPerSec, double p95LatencyMs) { }

    private record Analysis(String reasonCode, String nextAction, boolean profilerEnabled, boolean mstaEnabled,
            String profilerSessionId) {
    }

    private record RequestDetails(String method, Map<String, String> headers, String body, int timeoutMs,
            String healthCheckUrl) {
    }

    private record ObservationTargets(List<String> requiredLineHits, String probeId, String probeBaseUrl) { }
}
