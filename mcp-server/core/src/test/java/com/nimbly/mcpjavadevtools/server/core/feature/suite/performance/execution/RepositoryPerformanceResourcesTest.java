package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterExecutableResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterJmxRenderer;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterJtlCollector;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterWorkloadExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the guide's full Performance validation without making requests or running a workload. */
class RepositoryPerformanceResourcesTest {
    @TempDir
    Path workspace;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger healthChecks = new AtomicInteger();

    @Test
    void guidePerformanceContractReachesTheStubbedHealthBoundaryAfterSemanticValidation() throws Exception {
        var result = executor().executePlan(guideInput());
        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reasonCode()).isEqualTo("performance_target_healthcheck_failed");
        assertThat(healthChecks).hasValue(1);
        assertThat(Files.exists(workspace.resolve("run"))).isFalse();
    }

    @ParameterizedTest(name = "guide invalid Performance threshold: {0}")
    @ValueSource(strings = {"maxErrorRatePct", "minThroughputPerSec", "p95LatencyMs"})
    void guideThresholdCorruptionIsRejectedBeforeAnyExternalWork(String threshold) throws Exception {
        ObjectNode input = guideInput();
        ((ObjectNode) input.path("contract").path("successCriteria")).put(threshold, -1);
        var result = executor().executePlan(input);
        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reasonCode()).isEqualTo("performance_threshold_invalid");
        assertThat(healthChecks).hasValue(0);
    }

    private ObjectNode guideInput() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("mcp-server/pom.xml"))) {
            root = root.getParent();
        }
        assertThat(root).isNotNull();
        Path plan = root.resolve("docs/guides/complete-project-example/"
                + ".mcpjvm/example-project/plans/performance/load-profile");
        ObjectNode metadata = (ObjectNode) mapper.readTree(plan.resolve("metadata.json").toFile());
        assertThat(metadata.path("suiteType").asText()).isEqualTo("performance");
        assertThat(metadata.path("execution").path("intent").asText()).isEqualTo("performance");
        ObjectNode contract = (ObjectNode) mapper.readTree(plan.resolve("contract.json").toFile());
        assertThat(contract.path("loadModel").path("mode").asText()).isEqualTo("concurrency");
        for (String field : new String[] {"concurrency", "durationSeconds", "rampUpSeconds"}) {
            var value = contract.path("loadModel").path(field);
            assertThat(value.isIntegralNumber()).as(field).isTrue();
            assertThat(value.asLong()).as(field).isGreaterThanOrEqualTo("rampUpSeconds".equals(field) ? 0 : 1);
        }
        for (String field : new String[] {"maxErrorRatePct", "minThroughputPerSec", "p95LatencyMs"}) {
            var value = contract.path("successCriteria").path(field);
            assertThat(value.isNumber()).as(field).isTrue();
            assertThat(value.asDouble()).as(field).isGreaterThanOrEqualTo(0);
            if (!"maxErrorRatePct".equals(field)) {
                assertThat(value.asDouble()).as(field).isPositive();
            }
        }
        // Instantiate only the guide's illustrative request values; preserve its
        // load model, observation targets, provider, and thresholds unchanged.
        ObjectNode request = (ObjectNode) contract.path("entrypoints").get(0).path("request");
        assertThat(request.path("path").asText()).contains("{id}");
        request.put("path", request.path("path").asText().replace("{id}", "fixture-event"));
        ObjectNode input = mapper.createObjectNode();
        input.set("metadata", metadata);
        input.set("contract", contract);
        input.put("runDirectory", workspace.resolve("run").toString());
        return input;
    }

    private PerformancePlanExecutor executor() {
        var workload = new JmeterWorkloadExecutor(new JmeterJmxRenderer(),
                (executable, directory, jmx, jtl, log, timeout) -> {
                    throw new AssertionError("Guide validation must not run a workload");
                }, new JmeterJtlCollector());
        return new PerformancePlanExecutor(new JmeterExecutableResolver(), workload, null, request -> {
            healthChecks.incrementAndGet();
            assertThat(request.request()).containsEntry("url", "http://127.0.0.1:8080/actuator/health");
            return ExecuteTransportResult.httpResponse("fail", "http", 503, Map.of(), "", 1);
        });
    }
}
