package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("orchestration")
@Timeout(240)
class McpServerOrchestrationRoutingIT extends OrchestrationMcpSupport {
    @Test
    void securityProfilesExecuteOrderedRealCasesAndPersistFindingsAndCoverage() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, SecurityMcpSupport.context(workspace, "first", "second"));
            for (String name : new String[] {"first", "second"}) {
                ObjectNode plan = SecurityMcpSupport.generatedPlan(target.url(""));
                SecurityMcpSupport.entry(plan).withObject("transport").put("path", "/" + name);
                SecurityMcpSupport.custom(plan, "customCases", name, "allow");
                SecurityMcpSupport.save(server, name, plan);
            }
            JsonNode result = orchestrate(server, "security", "security", 0);
            assertStatus(result, "pass", "ok");
            assertThat(result.path("details").path("planRuns")).hasSize(2);
            for (String name : new String[] {"first", "second"}) {
                for (int i = 0; i < 3; i++) {
                    assertThat(target.received().uri()).isEqualTo("/" + name);
                }
            }
            target.assertNoRequest();
            checkpoint(workspace, result);
            JsonNode persisted = planRun(server, "security", "second", "security-2");
            assertThat(persisted.path("details").path("coverage").path("complete").asBoolean()).isTrue();
            assertThat(persisted.path("details").path("findings")).isEmpty();
        }
    }

    @Test
    void performanceProfilesExecuteRealJmeterAndResumeWithoutRewritingCompletedWorkload() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            PerformanceMcpSupport.saveContext(s, "first", "second");
            for (String name : new String[] {"first", "second"}) {
                PerformanceMcpSupport.save(s, name, PerformanceMcpSupport.workload(s));
            }
            JsonNode first = orchestrate(s.server, "performance", "workload", 1);
            assertStatus(first, "in_progress", "execution_plan_slice_complete");
            Path jtl = PerformanceMcpSupport.runDirectory(s, "first", "workload").resolve("workload.jmeter.jtl");
            byte[] original = Files.readAllBytes(jtl);
            assertThat(original).isNotEmpty();
            var modified = Files.getLastModifiedTime(jtl);
            s.server.close();
            try (McpServerProcess server = JvmProbeScenario.startServer(s.workspace)) {
                initialize(server);
                JsonNode result = orchestrate(server, "performance", "workload", 1);
                assertStatus(result, "pass", "ok");
                assertThat(result.path("details").path("planRuns")).hasSize(2);
                JsonNode persisted = planRun(server, "performance", "second", "workload-2");
                assertThat(persisted.path("details").path("metrics").path("totalRequests").asLong()).isPositive();
                assertThat(Files.readAllBytes(jtl)).isEqualTo(original);
                assertThat(Files.getLastModifiedTime(jtl)).isEqualTo(modified);
                checkpoint(s.workspace, result);
                JsonNode probe = execute(server, "probe.status", Map.of("baseUrl", s.baseUrl, "key", s.postKey()), true);
                assertThat(probe.path("result").path("actionResult").path("entries").get(0).path("hitCount").asLong()).isPositive();
                assertThat(persisted.path("details").path("requiredLineHits").get(0).asText()).isEqualTo(s.postKey());
                assertThat(execute(server, "jvm_lifecycle.deactivate", s.identity(), true)
                        .path("result").path("reasonCode").asText()).isEqualTo("deactivated");
            }
        }
    }
}
