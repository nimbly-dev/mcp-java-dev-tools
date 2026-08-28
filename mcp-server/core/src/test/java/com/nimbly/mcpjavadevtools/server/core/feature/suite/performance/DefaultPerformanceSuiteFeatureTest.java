package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.action.impl.ExecutePerformancePlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.execution.PerformancePlanExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.action.PerformanceSuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.request.PerformanceSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.DefaultJmeterProcessRunner;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterExecutableResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterJmxRenderer;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterJtlCollector;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterWorkloadExecutor;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultPerformanceSuiteFeatureTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void blocksMissingRequiredPlanFieldsWithStableReasonCode() throws Exception {
        PerformanceSuiteFeature feature = feature();

        var result = feature.execute(new PerformanceSuiteRequest(PerformanceSuiteAction.EXECUTE_PLAN, mapper.readTree("{}")));

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reasonCode()).isEqualTo("performance_plan_input_invalid");
    }

    @Test
    void blocksWhenConfiguredJmeterInstallationIsUnavailable() throws Exception {
        PerformanceSuiteFeature feature = feature();
        String input = """
                {"planName":"smoke","runDirectory":"target/performance-test","contract":{
                 "workloadProvider":{"type":"jmeter","mode":"generated_http","options":{"installationPath":"missing-jmeter"}},
                 "entrypoints":[{"transport":{"protocol":"http","baseUrl":"https://example.test"},"request":{"method":"GET","path":"/health"}}],
                 "loadModel":{"mode":"concurrency","concurrency":1,"rampUpSeconds":0,"durationSeconds":1},
                 "observationTargets":{"probeId":"demo","requiredLineHits":["demo.Sample#run:12"]},
                 "successCriteria":{"maxErrorRatePct":0,"minThroughputPerSec":1,"p95LatencyMs":100}}}
                """;

        var result = feature.execute(new PerformanceSuiteRequest(PerformanceSuiteAction.EXECUTE_PLAN, mapper.readTree(input)));

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reasonCode()).isEqualTo("performance_jmeter_missing");
    }

    private static PerformanceSuiteFeature feature() {
        JmeterWorkloadExecutor executor = new JmeterWorkloadExecutor(
                new JmeterJmxRenderer(), new DefaultJmeterProcessRunner(), new JmeterJtlCollector());
        PerformancePlanExecutor planExecutor = new PerformancePlanExecutor(new JmeterExecutableResolver(), executor);
        return new DefaultPerformanceSuiteFeature(List.of(new ExecutePerformancePlanAction(planExecutor)));
    }
}
