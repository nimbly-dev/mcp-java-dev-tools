package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("performance")
@Timeout(180)
class McpServerPerformanceBuiltinIT extends PerformanceMcpSupport {
    @Test
    void builtinAndOmittedProviderExecuteRealConcurrentRequestsAndStableThresholds() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s, "builtin", "default", "threshold", "errors");
            ObjectNode plan = workload(s);
            contract(plan).putObject("workloadProvider").put("type", "builtin");
            contract(plan).withObject("loadModel").put("rampUpSeconds", 1);
            for (String name : new String[] {"builtin", "default", "threshold", "errors"}) {
                if (name.equals("default")) {
                    contract(plan).remove("workloadProvider");
                } else if (name.equals("threshold")) {
                    contract(plan).withObject("successCriteria").put("minThroughputPerSec", 1.0e100);
                } else if (name.equals("errors")) {
                    entry(plan).withObject("request").put("path", "/api/v1/posts/999999");
                }
                save(s, name, plan);
                JsonNode result = run(s, name, name);
                assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("completed");
                String status = name.equals("builtin") || name.equals("default") ? "pass" : "fail";
                JsonNode metrics = result.path("details").path("metrics");
                assertThat(metrics.path("totalRequests").asInt()).isPositive();
                assertThat(metrics.path("errorRatePct").asDouble()).isEqualTo(name.equals("errors") ? 100 : 0);
                assertThat(result.path("details").path("requiredLineHits").get(0).asText()).isEqualTo(s.postKey());
                assertRun(s, name, name, status);
                JavaItScenario.observe("performance_suite.execute_plan", "ASSERTED", "builtin:" + name);
                assertNoWorkload(s, name);
            }
            s.deactivate();
        }
    }

    @Test
    void builtinUsesDefaultHeaderOverridesJsonBodyAndEncodedQueryTemplates() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start(); TransportHttpTarget target = TransportHttpTarget.start()) {
            s.attach();
            saveContext(s, "request");
            ObjectNode plan = workload(s);
            contract(plan).putObject("workloadProvider").put("type", "builtin");
            entry(plan).withObject("transport").put("baseUrl", target.url(""));
            entry(plan).withObject("transport").remove("healthCheckPath");
            entry(plan).withObject("transport").putObject("defaultHeaders").put("X-Choice", "default").put("X-Default", "kept");
            ObjectNode request = entry(plan).withObject("request").put("method", "POST").put("path", "/echo?replaced=old");
            request.putObject("headers").put("x-choice", "override").put("Content-Type", "application/json");
            request.putObject("queryTemplate").put("replaced", "a & b").put("number", 3);
            request.putObject("body").put("message", "quoted \"value\"").put("count", 2);
            save(s, "request", plan);
            JsonNode result = run(s, "request", "request");
            assertThat(result.path("reasonCode").asText()).isEqualTo("performance_required_line_hit_missing");
            TransportHttpTarget.Received received = target.received();
            assertThat(received.method()).isEqualTo("POST");
            assertThat(received.uri()).isEqualTo("/echo?replaced=a+%26+b&number=3");
            assertThat(received.header("X-Choice")).isEqualTo("override");
            assertThat(received.header("X-Default")).isEqualTo("kept");
            assertThat(JSON.readTree(received.body()).path("message").asText()).isEqualTo("quoted \"value\"");
            assertRun(s, "request", "request", "blocked");
            s.deactivate();
        }
    }

    @Test
    void builtinTimeoutIsBlockedAndDoesNotLeaveWorkersOrAnOwnedProfilerRunning() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (JvmProbeScenario s = JvmProbeScenario.start(); TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            TransportHttpTarget.respond(exchange, 200, "{}");
        })) {
            s.attach();
            saveContext(s, "timeout");
            ObjectNode plan = workload(s);
            contract(plan).putObject("workloadProvider").put("type", "builtin");
            entry(plan).withObject("transport").put("baseUrl", target.url(""));
            entry(plan).withObject("transport").remove("healthCheckPath");
            entry(plan).withObject("request").put("timeoutMs", 100);
            contract(plan).putObject("analysis").putObject("profiler").put("enabled", true);
            save(s, "timeout", plan);
            assertBlocked(s, "timeout", "transport_request_timeout");
            assertThat(requests.get()).isPositive();
            int completed = requests.get();
            Thread.sleep(600);
            assertThat(requests.get()).isEqualTo(completed);
            assertThat(s.success("profiler", Map.of("action", "status")).path("status").asText()).isEqualTo("completed");
            s.success("profiler", Map.of("action", "reset"));
            s.deactivate();
        }
    }
}
