package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.analysis.PerformanceAnalysisOptions;
import java.nio.file.Path;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.Event;
import jdk.jfr.Name;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceJfrEvidenceCollectorTest {

    @Test
    void oneSparseWallSampleUsesItsElapsedWeightInsteadOfTheWholeWorkload(@TempDir Path directory) throws Exception {
        Path jfr = wallCapture(directory, true);
        var options = PerformanceAnalysisOptions.parse(new ObjectMapper().readTree("""
                {"executionTiming":{"enabled":true,"event":"wall"},"msta":{"enabled":true}}
                """));
        var evidence = new PerformanceJfrEvidenceCollector().collect(jfr, directory, List.of("a.Controller#get:12"), "plan", "run",
                new PerformanceJfrEvidenceCollector.Workload("GET", "/", 1, 0, 60, true, options, "jfr"));
        var summary = new ObjectMapper().valueToTree(evidence.msta());
        assertThat(summary.path("methods").get(0).path("estimatedTimeMs").asDouble()).isEqualTo(20.0);
        assertThat(summary.path("timingBasis").asText()).isEqualTo("sampled_wall_time");
        assertThat(summary.path("sampling").path("availableProcessors").asInt()).isEqualTo(4);
    }

    @Test
    void partialWallRecordingCannotProduceSuccessfulTimingEvidence(@TempDir Path directory) throws Exception {
        Path jfr = wallCapture(directory, false);
        var options = PerformanceAnalysisOptions.parse(new ObjectMapper().readTree("""
                {"executionTiming":{"enabled":true,"event":"wall"},"msta":{"enabled":true}}
                """));
        assertThatThrownBy(() -> new PerformanceJfrEvidenceCollector().collect(jfr, directory, List.of("a.Controller#get:12"),
                "plan", "run", new PerformanceJfrEvidenceCollector.Workload("GET", "/", 1, 0, 60, true, options, "jfr")))
                .isInstanceOf(java.io.IOException.class).hasMessage("performance_wall_sampling_incomplete");
    }

    @Test
    void aMissingRequestedMethodIsPartialInsteadOfAFullTimingSuccess(@TempDir Path directory) throws Exception {
        Path jfr = wallCapture(directory, true);
        var options = PerformanceAnalysisOptions.parse(new ObjectMapper().readTree("""
                {"executionTiming":{"enabled":true,"event":"wall"},
                 "msta":{"enabled":true,"methodTargets":["a.Controller#get","a.Controller#missing"]}}
                """));
        var evidence = new PerformanceJfrEvidenceCollector().collect(jfr, directory, List.of("a.Controller#get:12"), "plan", "run",
                new PerformanceJfrEvidenceCollector.Workload("GET", "/", 1, 0, 60, true, options, "jfr"));
        assertThat(evidence.msta()).containsEntry("status", "partial_anchor_samples")
                .containsEntry("missingMethodTargets", List.of("a.Controller#missing"));
    }

    @Test
    void compressedStackCannotExpandBeyondTheDecodeCeiling(@TempDir Path directory) throws Exception {
        Path jfr = wallCapture(directory, true, "a.Controller#get".repeat(2000));
        var options = PerformanceAnalysisOptions.parse(new ObjectMapper().readTree("""
                {"executionTiming":{"enabled":true,"event":"wall"},"msta":{"enabled":true}}
                """));
        assertThatThrownBy(() -> new PerformanceJfrEvidenceCollector().collect(jfr, directory, List.of("a.Controller#get:12"),
                "plan", "run", new PerformanceJfrEvidenceCollector.Workload("GET", "/", 1, 0, 60, true, options, "jfr")))
                .isInstanceOf(java.io.IOException.class).hasMessage("performance_wall_stack_invalid");
    }

    @Test
    void manyCompressedDefinitionsCannotExceedTheAggregateMemoryCeiling(@TempDir Path directory) throws Exception {
        Path jfr = directory.resolve("definitions.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(WallStack.class);
            recording.start();
            String encoded = encodedStack("a.Controller#get".repeat(1000));
            for (int index = 1; index <= 280; index++) {
                WallStack stack = new WallStack();
                stack.sessionId = "run";
                stack.stackId = index;
                stack.encodedFrames = encoded;
                stack.commit();
            }
            recording.stop();
            recording.dump(jfr);
        }
        var options = PerformanceAnalysisOptions.parse(new ObjectMapper().readTree("""
                {"executionTiming":{"enabled":true,"event":"wall"},"msta":{"enabled":true}}
                """));
        assertThatThrownBy(() -> new PerformanceJfrSamplingReader(options, "run").read(jfr))
                .isInstanceOf(java.io.IOException.class).hasMessage("performance_wall_stack_invalid");
    }

    private Path wallCapture(Path directory, boolean complete) throws Exception {
        return wallCapture(directory, complete, "a.Controller#get");
    }

    private Path wallCapture(Path directory, boolean complete, String frames) throws Exception {
        Path path = directory.resolve("wall.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(WallSample.class);
            recording.enable(WallStack.class);
            recording.enable(WallStatus.class);
            recording.start();
            WallStack stack = new WallStack();
            stack.sessionId = "run";
            stack.stackId = 1;
            stack.encodedFrames = encodedStack(frames);
            WallSample sample = new WallSample();
            sample.sessionId = "run";
            sample.sampleDurationNanos = 20_000_000L;
            sample.stackId = 1;
            sample.commit();
            stack.commit();
            WallStatus status = new WallStatus();
            status.sessionId = "run";
            status.complete = complete;
            status.intervalNanos = 20_000_000L;
            status.availableProcessors = 4;
            status.commit();
            // JFR events are JVM-global: another capture must not alter this session's timing or completion state.
            WallSample foreign = new WallSample();
            foreign.sessionId = "other-capture";
            foreign.sampleDurationNanos = 60_000_000_000L;
            foreign.stackId = 1;
            foreign.commit();
            WallStatus other = new WallStatus();
            other.sessionId = "other-capture";
            other.complete = false;
            other.commit();
            recording.stop();
            recording.dump(path);
        }
        return path;
    }

    private String encodedStack(String frames) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(bytes)) {
            gzip.write(frames.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    @Name("mcpjvm.WallClockSample")
    static class WallSample extends Event {
        String sessionId;
        long sampleDurationNanos;
        long stackId;
    }

    @Name("mcpjvm.WallClockStack")
    static class WallStack extends Event {
        String sessionId;
        long stackId;
        String encodedFrames;
    }

    @Name("mcpjvm.WallClockSamplingStatus")
    static class WallStatus extends Event {
        String sessionId;
        boolean complete;
        long intervalNanos;
        int availableProcessors;
    }

    @Test
    void explicitDisabledCorrelationProducesNoArtifactWithOrWithoutMsta(@TempDir Path directory) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var options = PerformanceAnalysisOptions.parse(mapper.readTree("""
                {"executionTiming":{"enabled":true},"msta":{"enabled":true},"correlation":{"enabled":false}}
                """));
        Path jfr = directory.resolve("capture.jfr");
        try (Recording recording = new Recording()) {
            recording.start();
            recording.stop();
            recording.dump(jfr);
        }
        PerformanceJfrEvidenceCollector collector = new PerformanceJfrEvidenceCollector();
        var evidence = collector.collect(jfr, directory, List.of("a.Controller#get:12"), "plan", "run",
                new PerformanceJfrEvidenceCollector.Workload("GET", "/", 1, 0, 1, true, options, "jfr"));

        assertThat(evidence.correlation()).containsEntry("status", "disabled").containsEntry("attributions", List.of());
        assertThat(evidence.correlationPath()).isNull();
        assertThat(directory.resolve("correlation/correlation.json")).doesNotExist();
        assertThat(evidence.mstaPath()).isRegularFile();
        assertThat(collector.notConfigured(directory, "disabled", false).correlation()).isEqualTo(evidence.correlation());
    }

    @Test
    void persistsCanonicalCorrelationIdentityAndLineHitEvidence(@TempDir Path directory) throws Exception {
        Path jfr = directory.resolve("capture.jfr");
        try (Recording recording = new Recording()) {
            recording.start();
            recording.stop();
            recording.dump(jfr);
        }
        var evidence = new PerformanceJfrEvidenceCollector().collect(jfr, directory, List.of("a.Controller#get:12"),
                "performance-plan", "run-1", new PerformanceJfrEvidenceCollector.Workload(
                        "GET", "/orders/{id}", 4, 1, 5));
        var correlation = new ObjectMapper().readTree(evidence.correlationPath().toFile());

        assertThat(correlation.path("suite").asText()).isEqualTo("performance");
        assertThat(correlation.path("run").path("runId").asText()).isEqualTo("run-1");
        assertThat(correlation.path("workloadIdentity").path("entrypoint").path("pathTemplate").asText())
                .isEqualTo("/orders/{id}");
        assertThat(correlation.path("workloadIdentity").path("loadModel").path("concurrency").asInt()).isEqualTo(4);
        assertThat(correlation.path("evidence").path("lineHits").get(0).path("strictLineKey").asText())
                .isEqualTo("a.Controller#get:12");
        assertThat(correlation.path("evidence").has("requiredLineHits")).isFalse();
    }
}
