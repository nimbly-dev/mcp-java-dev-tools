package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("security")
@Timeout(120)
class McpServerSecurityPolicyIT extends SecurityMcpSupport {
    @Test
    void incompleteMatrixPreservesItsPreviouslyConfirmedFindingForDiagnostics() throws Exception {
        Path workspace = JavaItScenario.workspace();
        java.util.concurrent.atomic.AtomicInteger secondBaselines = new java.util.concurrent.atomic.AtomicInteger();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            boolean second = exchange.getRequestURI().getPath().equals("/second");
            int status = second && secondBaselines.incrementAndGet() > 1 ? 503 : 200;
            TransportHttpTarget.respond(exchange, status, "{}");
        }); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "partial"));
            ObjectNode plan = generatedPlan(target.url(""));
            packs(plan, "web-api-core");
            contract(plan).withObject("targetBoundary").putObject("fixtureContext").put("safeInput", "foreign");
            entry(plan).putObject("baseline").putObject("query").put("q", "own");
            ObjectNode second = entry(plan).deepCopy().put("id", "second");
            second.withObject("transport").put("path", "/second");
            contract(plan).withArray("entrypoints").add(second);
            save(server, "partial", plan);
            JsonNode result = run(server, "partial", "partial");
            assertBlocked(result, "security_generated_baseline_unexpected");
            assertThat(result.path("details").path("coverage").path("plannedCount").asInt()).isEqualTo(4);
            assertThat(result.path("details").path("cases").get(2).path("outcome").asText()).isEqualTo("confirmed");
            assertThat(result.path("details").path("findings")).hasSize(1);
            assertThat(result.path("details").path("findings").get(0).path("outcome").asText()).isEqualTo("confirmed");
            for (int i = 0; i < 5; i++) {
                target.received();
            }
            target.assertNoRequest();
            JsonNode persisted = assertRun(server, workspace, "partial", "partial", "blocked");
            assertThat(persisted.path("details").path("findings")).isEqualTo(result.path("details").path("findings"));
        }
    }

    @Test
    void retainedSeveritySelectionChangesVerdictWithoutHidingConfirmedFindings() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "severity"));
            ObjectNode plan = generatedPlan(target.url(""));
            packs(plan, "web-api-core");
            contract(plan).withObject("targetBoundary").putObject("fixtureContext").put("safeInput", "foreign");
            entry(plan).putObject("baseline").putObject("query").put("q", "own");
            contract(plan).withObject("verdictPolicy").putArray("failOnSeverity");
            save(server, "severity", plan);
            JsonNode result = run(server, "severity", "severity");
            completed(result, "pass", 2);
            assertThat(result.path("details").path("findings")).hasSize(1);
            assertThat(result.path("details").path("findings").get(0).path("outcome").asText()).isEqualTo("confirmed");
            for (int i = 0; i < 3; i++) {
                target.received();
            }
            target.assertNoRequest();
            assertRun(server, workspace, "severity", "severity", "pass");
        }
    }

    @Test
    void retainedRequestRatePolicyBoundsEveryBaselineAndMutation() throws Exception {
        Path workspace = JavaItScenario.workspace();
        List<Long> arrivals = new CopyOnWriteArrayList<>();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            arrivals.add(System.nanoTime());
            TransportHttpTarget.respond(exchange, 200, "{}");
        }); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "rate"));
            ObjectNode plan = generatedPlan(target.url(""));
            packs(plan, "web-api-core");
            contract(plan).withObject("safetyPolicy").put("maxRequestsPerSecond", 5);
            contract(plan).withObject("targetBoundary").putObject("fixtureContext").put("safeInput", "foreign");
            entry(plan).putObject("baseline").putObject("query").put("q", "own");
            save(server, "rate", plan);
            completed(run(server, "rate", "rate"), "fail", 2);
            assertThat(arrivals).hasSize(3);
            assertThat(Duration.ofNanos(arrivals.get(1) - arrivals.get(0))).isGreaterThan(Duration.ofMillis(140));
            assertThat(Duration.ofNanos(arrivals.get(2) - arrivals.get(1))).isGreaterThan(Duration.ofMillis(140));
        }
    }

    @Test
    void retainedDurationPolicyBoundsRealHttpAndPersistsTheEntirePlannedMatrix() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            TransportHttpTarget.respond(exchange, 200, "{}");
        }); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "duration"));
            ObjectNode plan = generatedPlan(target.url(""));
            contract(plan).withObject("safetyPolicy").put("maxDurationMs", 300);
            save(server, "duration", plan);
            long started = System.nanoTime();
            JsonNode result = run(server, "duration", "duration");
            assertBlocked(result, "security_blackbox_duration_exceeded");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            assertThat(result.path("details").path("coverage").path("plannedCount").asInt()).isEqualTo(10);
            assertThat(result.path("details").path("cases")).hasSize(1);
            target.received();
            target.assertNoRequest();
            assertRun(server, workspace, "duration", "duration", "blocked");
        }
    }
}
