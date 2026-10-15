package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("performance")
@Timeout(240)
class McpServerPerformanceProfilerIT extends PerformanceMcpSupport {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void disabledCorrelationRetainsJfrAndRequestedMstaWithoutAttributionArtifacts(boolean mstaEnabled) throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s, "no-correlation");
            ObjectNode plan = workload(s);
            contract(plan).putObject("workloadProvider").put("type", "builtin");
            contract(plan).withObject("loadModel").put("concurrency", 4).put("durationSeconds", mstaEnabled ? 10 : 2);
            ObjectNode analysis = contract(plan).putObject("analysis");
            analysis.putObject("executionTiming").put("enabled", true);
            analysis.putObject("msta").put("enabled", mstaEnabled);
            analysis.putObject("correlation").put("enabled", false).put("requireMsta", true);
            save(s, "no-correlation", plan);
            JsonNode result = run(s, "no-correlation", "no-correlation");
            JsonNode details = result.path("details");
            assertThat(details.path("runStatus").asText()).as(result.toPrettyString()).isEqualTo("pass");
            assertThat(details.path("correlation").path("status").asText()).isEqualTo("disabled");
            assertThat(details.path("correlation").path("attributions")).isEmpty();
            assertThat(details.path("artifacts").has("correlationPath")).isFalse();
            assertThat(runDirectory(s, "no-correlation", "no-correlation").resolve("correlation/correlation.json")).doesNotExist();
            assertThat(Path.of(details.path("artifacts").path("jfrPath").asText())).isRegularFile();
            assertThat(details.path("msta").path("status").asText()).isEqualTo(mstaEnabled ? "available" : "disabled");
            if (mstaEnabled) {
                assertThat(Path.of(details.path("artifacts").path("mstaPath").asText())).isRegularFile();
            }
            assertThat(assertRun(s, "no-correlation", "no-correlation", "pass").path("details").path("correlation"))
                    .isEqualTo(details.path("correlation"));
            JavaItScenario.observe("performance_suite.execute_plan", "ASSERTED", "correlation-disabled:msta=" + mstaEnabled);
            s.success("profiler", Map.of("action", "reset"));
            s.deactivate();
        }
    }

    @Test
    @Tag("skill-workflows")
    void realWorkloadOwnsJfrLifecycleSampledMethodAttributionAndCorrelation() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s, "profile");
            ObjectNode plan = workload(s);
            contract(plan).withObject("loadModel").put("concurrency", 4).put("durationSeconds", 10);
            ObjectNode analysis = contract(plan).putObject("analysis");
            analysis.putObject("executionTiming").put("enabled", true);
            analysis.putObject("msta").put("enabled", true).putObject("anchorSelection")
                    .put("source", "verified_required_line_hits").put("strategy", "all");
            save(s, "profile", plan);
            JsonNode result = run(s, "profile", "profile");
            assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("completed");
            assertThat(result.path("details").path("runStatus").asText()).isEqualTo("pass");
            JsonNode details = result.path("details");
            assertThat(details.path("profiler").path("status").asText()).isEqualTo("stopped");
            assertThat(details.path("profiler").path("provider").asText()).isEqualTo("jfr");
            Path jfr = Path.of(details.path("artifacts").path("jfrPath").asText());
            assertThat(jfr.startsWith(runDirectory(s, "profile", "profile"))).isTrue();
            assertThat(Files.size(jfr)).isPositive();
            assertThat(RecordingFile.readAllEvents(jfr)).anySatisfy(event ->
                    assertThat(event.getEventType().getName()).isEqualTo("mcpjvm.WallClockSample"));
            assertSampledEvidence(s, details);
            JsonNode persisted = assertRun(s, "profile", "profile", "pass");
            assertThat(persisted.path("details").path("msta")).isEqualTo(details.path("msta"));
            assertThat(s.success("profiler", Map.of("action", "status")).path("status").asText()).isEqualTo("completed");
            s.success("profiler", Map.of("action", "reset"));
            assertThat(s.success("profiler", Map.of("action", "status")).path("status").asText()).isEqualTo("idle");
            s.deactivate();
        }
    }

    private void assertSampledEvidence(JvmProbeScenario s, JsonNode details) throws Exception {
        JsonNode msta = details.path("msta");
        assertThat(msta.path("status").asText()).isEqualTo("available");
        assertThat(msta.path("targets")).hasSize(1);
        JsonNode target = msta.path("targets").get(0);
        assertThat(target.path("strictLineKey").asText()).isEqualTo(s.postKey());
        assertThat(target.path("anchorMethod").asText()).isEqualTo(JvmProbeScenario.CONTROLLER + "#getPost");
        assertThat(target.path("anchoredSampleCount").asInt()).isPositive();
        JsonNode correlation = details.path("correlation");
        assertThat(correlation.path("kind").asText()).isEqualTo("sampled_attribution");
        assertThat(correlation.path("status").asText()).isEqualTo("available");
        assertThat(correlation.path("attributions").get(0).path("strictLineKey").asText()).isEqualTo(s.postKey());
        for (String field : new String[] {"mstaPath", "correlationPath"}) {
            Path path = Path.of(details.path("artifacts").path(field).asText());
            assertThat(path.startsWith(runDirectory(s, "profile", "profile"))).isTrue();
            assertThat(JSON.readTree(path.toFile())).isEqualTo(field.equals("mstaPath") ? msta : correlation);
        }
    }

    @Test
    void busyProfilerBlocksWithoutTakingOverAnotherSession() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s, "busy");
            ObjectNode plan = workload(s);
            contract(plan).putObject("analysis").putObject("profiler").put("enabled", true);
            save(s, "busy", plan);
            s.success("profiler", Map.of("action", "start", "provider", "jfr", "sessionId", "other"));
            try {
                assertBlocked(s, "busy", "performance_profiler_start_failed");
                assertNoWorkload(s, "busy");
                assertThat(s.success("profiler", Map.of("action", "status")).path("sessionId").asText()).isEqualTo("other");
            } finally {
                s.success("profiler", Map.of("action", "stop", "sessionId", "other"));
                s.success("profiler", Map.of("action", "reset"));
            }
            s.deactivate();
        }
    }

    @ParameterizedTest
    @CsvSource({"method_targets,jfr,cpu", "target_plus_path,auto,wall"})
    void retainedBuiltinAnalysisModesUseExplicitTargetsAndRealFilteredStacks(String mode, String provider, String event) throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s, "analysis");
            ObjectNode plan = workload(s);
            contract(plan).putObject("workloadProvider").put("type", "builtin");
            contract(plan).withObject("loadModel").put("concurrency", 4).put("durationSeconds", 10);
            ObjectNode analysis = contract(plan).putObject("analysis");
            analysis.putObject("executionTiming").put("enabled", true).put("provider", provider)
                    .put("event", event).put("intervalNanos", 1_000_000).put("outputFormat", "jfr");
            ObjectNode msta = analysis.putObject("msta").put("enabled", true).put("mode", mode).put("allowThirdPartyFrames", false);
            msta.putArray("methodTargets").addObject().put("methodRef", JvmProbeScenario.CONTROLLER + "#getPost");
            msta.putArray("includePackages").add("com.example.social.");
            correlation(analysis, true);
            save(s, "analysis", plan);
            JsonNode result = run(s, "analysis", "analysis");
            assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("completed");
            JsonNode summary = result.path("details").path("msta");
            assertThat(summary.path("mode").asText()).isEqualTo(mode);
            assertThat(summary.path("methods")).hasSize(1);
            JsonNode method = summary.path("methods").get(0);
            assertThat(method.path("methodRef").asText()).isEqualTo(JvmProbeScenario.CONTROLLER + "#getPost");
            assertThat(method.path("samples").asInt()).isPositive();
            assertThat(method.path("pathSteps")).allSatisfy(step -> {
                assertThat(step.path("methodRef").asText()).startsWith("com.example.social.");
                assertThat(step.path("estimatedTimePct").asDouble()).isBetween(0.0, 100.0);
            });
            if (mode.equals("method_targets")) {
                assertThat(method.path("pathSteps")).hasSize(1);
            }
            assertThat(result.path("details").path("correlation").path("workloadIdentity").path("provider").path("type").asText())
                    .isEqualTo("builtin");
            assertThat(result.path("details").path("correlation").path("status").asText()).isEqualTo("available");
            assertRun(s, "analysis", "analysis", "pass");
            JavaItScenario.observe("performance_suite.execute_plan", "ASSERTED", mode + ":" + provider + ":" + event);
            s.success("profiler", Map.of("action", "reset"));
            s.deactivate();
        }
    }

    @Test
    void profilerOnlyDisabledMstaAndUnsampledExplicitTargetsKeepHonestEvidenceStates() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s, "disabled", "optional", "required");
            ObjectNode plan = workload(s);
            contract(plan).putObject("workloadProvider").put("type", "builtin");
            ObjectNode analysis = contract(plan).putObject("analysis");
            analysis.putObject("executionTiming").put("enabled", true).put("provider", "jfr");
            analysis.putObject("msta").put("enabled", false);
            save(s, "disabled", plan);
            JsonNode disabled = run(s, "disabled", "disabled");
            assertThat(disabled.path("details").path("msta").path("status").asText()).isEqualTo("disabled");
            assertThat(Path.of(disabled.path("details").path("artifacts").path("jfrPath").asText())).isRegularFile();
            assertRun(s, "disabled", "disabled", "pass");
            analysis.withObject("msta").put("enabled", true).put("mode", "method_targets")
                    .putArray("methodTargets").add("missing.Type#unexecuted");
            correlation(analysis, false);
            save(s, "optional", plan);
            JsonNode optional = run(s, "optional", "optional");
            assertThat(optional.path("details").path("msta").path("status").asText()).isEqualTo("no_anchor_samples");
            assertThat(optional.path("details").path("correlation").path("status").asText()).isEqualTo("unavailable");
            assertThat(optional.path("details").path("correlation").path("attributions")).isEmpty();
            correlation(analysis, true);
            save(s, "required", plan);
            assertBlocked(s, "required", "performance_msta_no_anchor_samples");
            s.success("profiler", Map.of("action", "reset"));
            s.deactivate();
        }
    }

    private void correlation(ObjectNode analysis, boolean required) {
        analysis.putObject("correlation").put("enabled", true).put("kind", "sampled_attribution")
                .put("anchorSource", "msta_resolved_anchors").put("requireLineHit", true).put("requireMsta", required);
    }

    @Test
    void explicitNativeProviderAndUnsupportedJfrEventsRespectTheSidecarPlatformContract() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s, "native", "event");
            ObjectNode plan = workload(s);
            contract(plan).putObject("workloadProvider").put("type", "builtin");
            ObjectNode timing = contract(plan).putObject("analysis").putObject("executionTiming")
                    .put("enabled", true).put("provider", "async-profiler").put("event", "wall");
            save(s, "native", plan);
            boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
            if (windows) {
                assertBlocked(s, "native", "performance_profiler_start_failed");
                assertNoWorkload(s, "native");
            } else {
                JsonNode result = run(s, "native", "native");
                assertThat(result.path("details").path("profiler").path("provider").asText()).isEqualTo("async-profiler");
                Path jfr = Path.of(result.path("details").path("artifacts").path("jfrPath").asText());
                assertThat(RecordingFile.readAllEvents(jfr)).isNotEmpty();
                assertRun(s, "native", "native", "pass");
                s.success("profiler", Map.of("action", "reset"));
            }
            timing.put("provider", "jfr").put("event", "unsupported-event");
            save(s, "event", plan);
            assertBlocked(s, "event", "performance_profiler_start_failed");
            s.deactivate();
        }
    }
}
