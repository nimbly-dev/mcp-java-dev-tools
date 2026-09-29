package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeBatchResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeBatchStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusEntry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.DefaultJmeterProcessRunner;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterExecutableResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterJmxRenderer;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterJtlCollector;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterWorkloadExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformancePlanExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void blocksMissingRequiredPlanFieldsWithStableReasonCode() throws Exception {
        PerformancePlanExecutor executor = executor();

        var result = executor.executePlan(mapper.readTree("{}"));

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reasonCode()).isEqualTo("performance_plan_input_invalid");
    }

    @Test
    void blocksWhenConfiguredJmeterInstallationIsUnavailable() throws Exception {
        PerformancePlanExecutor executor = executor();
        String input = """
                {"planName":"smoke","runDirectory":"target/performance-test","contract":{
                 "workloadProvider":{"type":"jmeter","mode":"generated_http","options":{"installationPath":"missing-jmeter"}},
                 "entrypoints":[{"transport":{"protocol":"http","baseUrl":"https://example.test"},"request":{"method":"GET","path":"/health"}}],
                 "loadModel":{"mode":"concurrency","concurrency":1,"rampUpSeconds":0,"durationSeconds":1},
                 "observationTargets":{"probeId":"demo","requiredLineHits":["demo.Sample#run:12"]},
                 "successCriteria":{"maxErrorRatePct":0,"minThroughputPerSec":1,"p95LatencyMs":100}}}
                """;

        var result = executor.executePlan(mapper.readTree(input));

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reasonCode()).isEqualTo("performance_jmeter_missing");
    }

    @Test
    void acceptsPostWorkloadProbeStatusAndNormalizesDuplicateRequiredKeys() throws Exception {
        Path executable = Files.createFile(temporaryDirectory.resolve("jmeter"));
        JmeterWorkloadExecutor workload = new JmeterWorkloadExecutor(
                new JmeterJmxRenderer(),
                (ignoredExecutable, ignoredRunDirectory, ignoredJmx, jtl, ignoredLog, ignoredTimeout) -> {
                    Files.writeString(jtl, "elapsed,success\n10,true\n");
                    return 0;
                },
                new JmeterJtlCollector());
        String key = "demo.Sample#run:12";
        List<List<String>> selectors = new ArrayList<>();
        ProbeFeature probe = request -> {
            if (request instanceof ProbeBatchResetRequest reset) {
                selectors.add(reset.keySelector().keys());
            }
            if (request instanceof ProbeBatchStatusRequest status) {
                selectors.add(status.keySelector().keys());
                return ProbeResult.success(new ProbeStatusResult(List.of(
                        new ProbeStatusEntry(key, key, 200, true, 3L, 1L, true,
                                "resolvable", null, null))));
            }
            return ProbeResult.success();
        };
        PerformancePlanExecutor executor = new PerformancePlanExecutor(
                new JmeterExecutableResolver(), workload, probe);
        String input = """
                {"planName":"smoke","runDirectory":"%s","contract":{
                 "workloadProvider":{"type":"jmeter","mode":"generated_http","options":{"installationPath":"%s"}},
                 "entrypoints":[{"transport":{"protocol":"http","baseUrl":"https://example.test"},"request":{"method":"GET","path":"/health"}}],
                 "loadModel":{"mode":"concurrency","concurrency":1,"rampUpSeconds":0,"durationSeconds":1},
                 "observationTargets":{"probeId":"demo","requiredLineHits":["%s","%s"]},
                 "successCriteria":{"maxErrorRatePct":0,"minThroughputPerSec":1,"p95LatencyMs":100}}}
                """.formatted(json(temporaryDirectory.resolve("run")), json(executable), key, key);

        var result = executor.executePlan(mapper.readTree(input));

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.details()).containsEntry("runStatus", "pass")
                .containsEntry("requiredLineHits", List.of(key));
        assertThat(selectors).containsExactly(List.of(key), List.of(key));
    }

    private static PerformancePlanExecutor executor() {
        JmeterWorkloadExecutor workload = new JmeterWorkloadExecutor(
                new JmeterJmxRenderer(), new DefaultJmeterProcessRunner(), new JmeterJtlCollector());
        return new PerformancePlanExecutor(new JmeterExecutableResolver(), workload);
    }

    private static String json(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
