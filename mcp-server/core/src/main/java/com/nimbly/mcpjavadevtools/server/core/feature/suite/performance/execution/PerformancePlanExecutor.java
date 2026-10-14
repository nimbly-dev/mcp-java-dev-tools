package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.PerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.result.PerformanceSuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.analysis.PerformanceAnalysisOptions;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.workload.PerformanceHttpWorkload;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.workload.PerformanceWorkloadResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.BuiltinPerformanceWorkload;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.health.PerformanceTargetHealthCheck;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterExecutableResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterWorkloadExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterWorkloadRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterWorkloadResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.execution.SuiteRuntimeEnvironment;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeBatchResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeBatchStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerCommand;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeyBatchSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates and executes the generated-HTTP JMeter Performance plan contract. */
public final class PerformancePlanExecutor implements PerformanceSuiteFeature {

    private static final int MAX_CONCURRENCY = 10_000;
    private static final int MAX_DURATION_SECONDS = 1_800;
    private static final Pattern CONTEXT_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9._-]+)}");
    private final JmeterExecutableResolver executableResolver;
    private final JmeterWorkloadExecutor workloadExecutor;
    private final ProbeFeature probe;
    private final PerformanceTargetHealthCheck healthCheck;
    private final PerformanceJfrEvidenceCollector jfrEvidenceCollector;
    private final BuiltinPerformanceWorkload builtin;

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
        this.builtin = new BuiltinPerformanceWorkload(transport);
    }

    /** Executes a plan or returns a deterministic preflight failure. */
    @Override
    public PerformanceSuiteResult executePlan(JsonNode input) {
        Validation validation = validate(input);
        if (validation.reasonCode() != null) {
            return blocked(validation.reasonCode(), validation.nextAction());
        }
        if (validation.builtin()) {
            return run(validation, null);
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
        PerformanceWorkloadResult result = executeWorkload(validation, executable);
        if (!result.completed()) {
            stopProfilerAfterWorkloadFailure(validation, profiler);
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
        if (evidence == null || (validation.analysis().requireMsta() && !"available".equals(evidence.msta().get("status")))) {
            return blocked("performance_msta_no_anchor_samples",
                    "collect JFR samples for every required Strict Line Key before completing the Performance run");
        }
        return completed(result, lineHits, validation.thresholds(), validation.durationSeconds(), captured, evidence);
    }

    private PerformanceWorkloadResult executeWorkload(Validation validation, String executable) {
        if (validation.builtin()) {
            return builtin.execute(new PerformanceHttpWorkload(validation.method(), validation.url(),
                    validation.headers(), validation.body(), validation.timeoutMs(), validation.concurrency(),
                    validation.rampUpSeconds(), validation.durationSeconds()));
        }
        JmeterWorkloadResult result = workloadExecutor.execute(new JmeterWorkloadRequest(
                validation.runDirectory(), validation.planName(), executable, validation.method(), validation.url(),
                validation.headers(), validation.body(), validation.environment(), validation.timeoutMs(), validation.concurrency(),
                validation.rampUpSeconds(), validation.durationSeconds()));
        return jmeterOutcome(result, validation);
    }

    private static PerformanceWorkloadResult jmeterOutcome(JmeterWorkloadResult result, Validation validation) {
        Map<String, Path> generated = Map.of("jmxPath", result.jmxPath(), "jtlPath", result.jtlPath(), "logPath", result.logPath());
        Map<String, Object> retained = new LinkedHashMap<>();
        try {
            for (var artifact : generated.entrySet()) {
                if (validation.artifactEmission().get(artifact.getKey())) {
                    retained.put(artifact.getKey(), artifact.getValue().toString());
                } else {
                    java.nio.file.Files.deleteIfExists(artifact.getValue());
                }
            }
        } catch (java.io.IOException exception) {
            return PerformanceWorkloadResult.blocked("performance_jmeter_artifact_failed");
        }
        return new PerformanceWorkloadResult(result.completed(), result.reasonCode(), result.totalRequests(), result.failedRequests(),
                result.latenciesMs(), retained);
    }

    private ProfilerEvidence startProfiler(Validation validation) {
        if (!validation.analysis().profilerEnabled()) {
            return ProfilerEvidence.notConfigured();
        }
        if (probe == null) {
            return ProfilerEvidence.blocked("performance_profiler_unavailable");
        }
        var result = probe.execute(new ProbeProfilerRequest(target(validation), ProbeProfilerCommand.START,
                validation.profilerSessionId(), validation.analysis().provider(), validation.analysis().event(),
                validation.analysis().intervalNanos(), null, "jfr", Duration.ofSeconds(15)));
        return profilerEvidence(result, "running", validation, "performance_profiler_start_failed");
    }

    private ProfilerEvidence stopProfiler(Validation validation, ProfilerEvidence profiler) {
        if (!profiler.started()) {
            return profiler;
        }
        var result = probe.execute(new ProbeProfilerRequest(target(validation), ProbeProfilerCommand.STOP,
                validation.profilerSessionId(), null, null, null, null, "jfr", Duration.ofSeconds(15)));
        return profilerEvidence(result, "completed", validation, "performance_profiler_stop_failed");
    }

    private void stopProfilerAfterWorkloadFailure(Validation validation, ProfilerEvidence profiler) {
        boolean interrupted = Thread.interrupted();
        try {
            stopProfiler(validation, profiler);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
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
            Validation validation,
            String failureCode) {
        return response.actionResult().filter(ProbeProfilerResult.class::isInstance)
                .map(ProbeProfilerResult.class::cast)
                .filter(result -> response.status().name().equals("SUCCESS") && expectedStatus.equals(result.status()))
                .filter(result -> validation.profilerSessionId().equals(result.sessionId()))
                .filter(result -> !"running".equals(expectedStatus) || "running".equals(result.detail()))
                .map(result -> new ProfilerEvidence(false, null, true, result.sessionId(), result.provider(),
                        result.outputPath(), result.outputFormat(), validation.capturePath()))
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
        if (!profiler.started() || !"available".equals(validation.analysis().mstaState())) {
            return jfrEvidenceCollector.notConfigured(validation.runDirectory(), validation.analysis().mstaState(),
                    validation.analysis().correlationEnabled());
        }
        try {
            return jfrEvidenceCollector.collect(profiler.capturePath(), validation.runDirectory(),
                    validation.requiredLineHits(), validation.planName(), validation.profilerSessionId(),
                    workload(validation, profiler));
        } catch (java.io.IOException exception) {
            return null;
        }
    }

    private static PerformanceJfrEvidenceCollector.Workload workload(Validation validation, ProfilerEvidence profiler) {
        return new PerformanceJfrEvidenceCollector.Workload(
                validation.method(), pathTemplate(validation.url()), validation.concurrency(),
                validation.rampUpSeconds(), validation.durationSeconds(), validation.builtin(), validation.analysis(), profiler.provider());
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
        var response = probe.execute(new ProbeBatchStatusRequest(target(validation),
                new ProbeKeyBatchSelector(validation.requiredLineHits()), Duration.ofSeconds(10)));
        if (!response.status().name().equals("SUCCESS")
                || response.actionResult().filter(ProbeStatusResult.class::isInstance).isEmpty()) {
            return null;
        }
        ProbeStatusResult status = (ProbeStatusResult) response.actionResult().orElseThrow();
        if (status.entries().size() != validation.requiredLineHits().size()) {
            return null;
        }
        for (int index = 0; index < status.entries().size(); index++) {
            if (!validation.requiredLineHits().get(index).equals(status.entries().get(index).requestedKey())
                    || !status.entries().get(index).lineHit()) {
                return null;
            }
        }
        return validation.requiredLineHits();
    }

    private static ProbeTargetSelector target(Validation validation) {
        return new ProbeTargetSelector(validation.probeId(), validation.probeBaseUrl());
    }

    private PerformanceSuiteResult completed(
            PerformanceWorkloadResult result,
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
        Map<String, Object> artifacts = new LinkedHashMap<>(result.artifacts());
        if (profiler.started()) {
            artifacts.put("jfrPath", profiler.outputPath());
        }
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
        RuntimeContext runtimeContext = runtimeContext(input);
        String installationPath = text(contract.path("workloadProvider").path("options").path("installationPath"));
        String url = requestUrl(input, contract, runtimeContext);
        String runDirectory = text(input.path("runDirectory"));
        int concurrency = positive(contract.path("loadModel").path("concurrency"));
        int durationSeconds = positive(contract.path("loadModel").path("durationSeconds"));
        ObservationTargets observationTargets = observationTargets(input, contract);
        Validation required = validateRequired(contract, url, runDirectory, concurrency, durationSeconds);
        if (required != null) {
            return required;
        }
        Validation observationFailure = validateObservation(observationTargets, concurrency, durationSeconds);
        if (observationFailure != null) {
            return observationFailure;
        }
        Thresholds thresholds = thresholds(contract.path("successCriteria"));
        if (thresholds == null) {
            return Validation.invalid("performance_threshold_invalid", "provide deterministic successCriteria thresholds");
        }
        String planName = textOr(input.path("planName"), "performance-plan");
        RequestDetails request = requestDetails(input, contract, runtimeContext);
        Validation contextFailure = validateRuntimeContext(url, request);
        if (contextFailure != null) {
            return contextFailure;
        }
        int rampUpSeconds = nonNegative(contract.path("loadModel").path("rampUpSeconds"));
        if (rampUpSeconds < 0) {
            return Validation.invalid("performance_plan_input_invalid", "provide a non-negative rampUpSeconds");
        }
        PerformanceAnalysisOptions analysis = PerformanceAnalysisOptions.parse(contract.path("analysis"));
        if (analysis.reasonCode() != null) {
            return Validation.invalid(analysis.reasonCode(), "provide valid retained Performance analysis settings");
        }
        return new Validation(null, null, installationPath, Path.of(runDirectory), planName, request.method(), url,
                request.headers(), request.body(), runtimeContext.environment(),
                request.timeoutMs(), concurrency, rampUpSeconds, durationSeconds, observationTargets.requiredLineHits(),
                observationTargets.probeId(), observationTargets.probeBaseUrl(), request.healthCheckUrl(), thresholds,
                analysis, textOr(input.path("suiteRunId"), planName + "-jfr"),
                Path.of(runDirectory).resolve("execution-timing.jfr").toAbsolutePath().normalize(), builtin(contract), artifactEmission(contract));
    }

    private static Validation validateObservation(
            ObservationTargets observationTargets, int concurrency, int durationSeconds) {
        if (observationTargets.requiredLineHits().isEmpty()
                || (observationTargets.probeId() == null && observationTargets.probeBaseUrl() == null)) {
            return Validation.invalid("performance_required_line_hit_missing", "provide requiredLineHits and a Probe selector");
        }
        if (concurrency > MAX_CONCURRENCY || durationSeconds > MAX_DURATION_SECONDS) {
            return Validation.invalid("performance_plan_bounds_invalid",
                    "reduce concurrency or duration to the supported hard ceiling");
        }
        return null;
    }

    private static Validation validateRuntimeContext(String url, RequestDetails request) {
        boolean unresolvedHeader = request.headers().values().stream().anyMatch(PerformancePlanExecutor::unresolved);
        if (unresolved(java.net.URLDecoder.decode(url, java.nio.charset.StandardCharsets.UTF_8))
                || unresolved(request.body()) || unresolvedHeader) {
            return Validation.invalid("performance_runtime_context_unresolved",
                    "provide every request context value through the execution profile or project bindings");
        }
        return null;
    }

    private static RequestDetails requestDetails(JsonNode input, JsonNode contract, RuntimeContext runtimeContext) {
        JsonNode entrypoint = contract.path("entrypoints").path(0);
        return new RequestDetails(
                textOr(entrypoint.path("request").path("method"), "GET"),
                resolveHeaders(requestHeaders(entrypoint), runtimeContext),
                requestBody(entrypoint.path("request").path("body"), runtimeContext),
                positiveOr(entrypoint.path("request").path("timeoutMs"), positiveOr(input.path("request").path("timeoutMs"), 30_000)),
                healthCheckUrl(entrypoint, runtimeContext));
    }

    private static Map<String, String> requestHeaders(JsonNode entrypoint) {
        Map<String, String> merged = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        merged.putAll(headers(entrypoint.path("transport").path("defaultHeaders")));
        merged.putAll(headers(entrypoint.path("request").path("headers")));
        return merged;
    }

    private static String requestBody(JsonNode body, RuntimeContext context) {
        if (body.isMissingNode() || body.isNull()) {
            return null;
        }
        if (body.isTextual()) {
            return resolve(body.asText(), context);
        }
        return resolveJson(body, context).toString();
    }

    private static JsonNode resolveJson(JsonNode node, RuntimeContext context) {
        if (node.isTextual()) {
            return com.fasterxml.jackson.databind.node.TextNode.valueOf(resolve(node.asText(), context));
        }
        if (node.isObject()) {
            var object = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(entry -> object.set(entry.getKey(), resolveJson(entry.getValue(), context)));
            return object;
        }
        if (node.isArray()) {
            var array = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            node.forEach(value -> array.add(resolveJson(value, context)));
            return array;
        }
        return node;
    }

    private static Validation validateRequired(
            JsonNode contract,
            String url,
            String runDirectory,
            int concurrency,
            int durationSeconds) {
        boolean fieldsMissing = url == null || runDirectory == null;
        boolean loadModelInvalid = concurrency == 0 || durationSeconds == 0;
        boolean providerInvalid = !builtin(contract) && (!"jmeter".equals(text(contract.path("workloadProvider").path("type")))
                || !"generated_http".equals(text(contract.path("workloadProvider").path("mode"))));
        boolean entrypointInvalid = contract.path("entrypoints").size() != 1
                || !"http".equals(contract.path("entrypoints").path(0).path("transport").path("protocol").asText());
        return fieldsMissing || loadModelInvalid || providerInvalid || entrypointInvalid || !httpUrl(url)
                ? Validation.invalid("performance_plan_input_invalid",
                        "provide request.url, runDirectory, concurrency, and durationSeconds")
                : null;
    }

    private static JsonNode contract(JsonNode input) {
        return input.path("contract").isObject() ? input.path("contract") : input;
    }

    private static boolean builtin(JsonNode contract) {
        return contract.path("workloadProvider").isMissingNode()
                || "builtin".equals(text(contract.path("workloadProvider").path("type")));
    }

    private static Map<String, Boolean> artifactEmission(JsonNode contract) {
        JsonNode options = contract.path("workloadProvider").path("options");
        return Map.of("jmxPath", options.path("emitJmx").asBoolean(true), "jtlPath", options.path("emitJtl").asBoolean(true),
                "logPath", options.path("emitLog").asBoolean(true));
    }

    private static boolean httpUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            return List.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String requestUrl(JsonNode input, JsonNode contract, RuntimeContext runtimeContext) {
        String resolved = url(contract.path("entrypoints"), runtimeContext);
        return resolved == null ? resolve(text(input.path("request").path("url")), runtimeContext)
                : queryUrl(resolved, contract.path("entrypoints").path(0).path("request").path("queryTemplate"), runtimeContext);
    }

    private static String url(JsonNode entrypoints, RuntimeContext runtimeContext) {
        JsonNode entrypoint = entrypoints.path(0);
        String baseUrl = resolve(text(entrypoint.path("transport").path("baseUrl")), runtimeContext);
        String path = resolve(text(entrypoint.path("request").path("path")), runtimeContext);
        if (baseUrl == null || path == null) {
            return null;
        }
        try {
            return URI.create(baseUrl).resolve(path).toString();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String healthCheckUrl(JsonNode entrypoint, RuntimeContext runtimeContext) {
        String baseUrl = resolve(text(entrypoint.path("transport").path("baseUrl")), runtimeContext);
        String healthCheckPath = resolve(text(entrypoint.path("transport").path("healthCheckPath")), runtimeContext);
        if (baseUrl == null || healthCheckPath == null) {
            return null;
        }
        try {
            return URI.create(baseUrl).resolve(healthCheckPath).toString();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String queryUrl(String url, JsonNode query, RuntimeContext context) {
        if (!query.isObject() || query.isEmpty()) {
            return url;
        }
        URI uri = URI.create(url);
        Map<String, String> values = new LinkedHashMap<>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                values.put(java.net.URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8), pair);
            }
        }
        query.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isNull()) {
                String value = resolve(entry.getValue().asText(), context);
                // Keep a valid URI here. The JMX renderer restores bound credential functions and encodes their evaluated values.
                values.put(entry.getKey(), encodeQuery(entry.getKey()) + "=" + encodeQuery(value));
            }
        });
        String base = url.split("[?#]", 2)[0];
        return base + "?" + String.join("&", values.values());
    }

    private static String encodeQuery(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
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
        if (!node.path("maxErrorRatePct").isNumber() || !node.path("minThroughputPerSec").isNumber()
                || !node.path("p95LatencyMs").isNumber()) {
            return null;
        }
        double maxErrorRatePct = node.path("maxErrorRatePct").asDouble(-1);
        double minThroughput = node.path("minThroughputPerSec").asDouble(-1);
        double p95Latency = node.path("p95LatencyMs").asDouble(-1);
        if (!Double.isFinite(maxErrorRatePct) || !Double.isFinite(minThroughput) || !Double.isFinite(p95Latency)
                || maxErrorRatePct < 0 || minThroughput <= 0 || p95Latency <= 0) {
            return null;
        }
        return new Thresholds(maxErrorRatePct, minThroughput, p95Latency);
    }

    private static double errorRatePct(PerformanceWorkloadResult result) {
        return result.totalRequests() == 0 ? 100.0 : result.failedRequests() * 100.0 / result.totalRequests();
    }

    private static double throughput(PerformanceWorkloadResult result, int durationSeconds) {
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
            PerformanceWorkloadResult result,
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

    private static Map<String, String> resolveHeaders(
            Map<String, String> headers,
            RuntimeContext runtimeContext) {
        Map<String, String> resolved = new LinkedHashMap<>();
        headers.forEach((name, value) -> resolved.put(name, resolve(value, runtimeContext)));
        return Map.copyOf(resolved);
    }

    private static RuntimeContext runtimeContext(JsonNode input) {
        Map<String, String> values = new LinkedHashMap<>();
        input.path("providedContext").fields().forEachRemaining(entry -> {
            if (entry.getValue().isValueNode() && !entry.getValue().isNull()) {
                values.put(entry.getKey(), entry.getValue().asText());
            }
        });
        Map<String, String> bindings = new LinkedHashMap<>();
        Map<String, String> environment = SuiteRuntimeEnvironment.load(input.path("credentialSource"));
        input.path("credentialBindings").fields().forEachRemaining(entry -> {
            String environmentKey = entry.getValue().asText();
            String value = values.getOrDefault(entry.getKey(), environment.get(environmentKey));
            if (value != null && !value.isBlank()) {
                values.put(entry.getKey(), value);
                bindings.put(entry.getKey(), environmentKey);
            }
        });
        Map<String, String> overrides = new LinkedHashMap<>();
        bindings.forEach((contextKey, environmentKey) -> overrides.put(environmentKey, values.get(contextKey)));
        return new RuntimeContext(Map.copyOf(values), Map.copyOf(bindings), Map.copyOf(overrides), !builtin(contract(input)));
    }

    private static String resolve(String value, RuntimeContext runtimeContext) {
        if (value == null) {
            return null;
        }
        Matcher matcher = CONTEXT_PLACEHOLDER.matcher(value);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String contextKey = matcher.group(1);
            String replacement = runtimeContext.jmeter() && runtimeContext.bindings().containsKey(contextKey)
                    ? "${__groovy(System.getenv('" + runtimeContext.bindings().get(contextKey) + "'))}"
                    : runtimeContext.values().get(contextKey);
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(
                    replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private static boolean unresolved(String value) {
        return value != null && CONTEXT_PLACEHOLDER.matcher(value).find();
    }

    private static List<String> lines(JsonNode node) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
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
        return node.isIntegralNumber() && node.canConvertToInt() && node.asInt() > 0 ? node.asInt() : 0;
    }

    private static int positiveOr(JsonNode node, int fallback) {
        return positive(node) == 0 ? fallback : positive(node);
    }

    private static int nonNegative(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt() && node.asInt() >= 0 ? node.asInt() : -1;
    }

    private static String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText().trim() : null;
    }

    private static String textOr(JsonNode node, String fallback) {
        String value = text(node);
        return value == null ? fallback : value;
    }

    private record Validation(String reasonCode, String nextAction, String installationPath, Path runDirectory, String planName,
            String method, String url, Map<String, String> headers, String body, Map<String, String> environment,
            int timeoutMs, int concurrency,
            int rampUpSeconds, int durationSeconds, List<String> requiredLineHits, String probeId, String probeBaseUrl,
            String healthCheckUrl, Thresholds thresholds, PerformanceAnalysisOptions analysis,
            String profilerSessionId, Path capturePath, boolean builtin, Map<String, Boolean> artifactEmission) {
        private static Validation invalid(String reasonCode, String nextAction) {
            return new Validation(reasonCode, nextAction, null, null, null, null, null, Map.of(), null, Map.of(), 0, 0, 0, 0,
                    List.of(), null, null, null, null, null, null, null, false, Map.of());
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

    private record RequestDetails(String method, Map<String, String> headers, String body, int timeoutMs,
            String healthCheckUrl) {
    }

    private record ObservationTargets(List<String> requiredLineHits, String probeId, String probeBaseUrl) { }

    private record RuntimeContext(
            Map<String, String> values,
            Map<String, String> bindings,
            Map<String, String> environment, boolean jmeter) {
    }
}
