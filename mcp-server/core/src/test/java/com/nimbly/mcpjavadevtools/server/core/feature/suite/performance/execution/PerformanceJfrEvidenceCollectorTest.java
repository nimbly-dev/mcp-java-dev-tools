package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceJfrEvidenceCollectorTest {

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
