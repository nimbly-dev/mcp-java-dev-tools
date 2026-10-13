package com.nimbly.mcpjavadevtools.server;

import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.EVENT_CONSUMER_INCLUDE;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.EVENT_CONSUMER_PROBE_ID;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.EVENT_PRODUCER_INCLUDE;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.EVENT_PRODUCER_PROBE_ID;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.LOOPBACK_HTTP_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;

@Tag("artifact")
@Tag("regression")
@Timeout(240)
class McpServerCorrelationIT extends McpServerStdioSupport {

    @Test
    void executableJarCorrelatesCrossServiceEventsWithConsumerRuntimeEvidence() throws Exception {
        Path fixtureRoot = repositoryRoot().resolve("test/fixtures/spring-apps/social-platform/event-service");
        Path producerJar = fixtureRoot.resolve(
                "event-producer-app/target/event-producer-app-0.1.0-SNAPSHOT.jar");
        Path consumerJar = fixtureRoot.resolve(
                "event-consumer-app/target/event-consumer-app-0.1.0-SNAPSHOT.jar");
        assertThat(producerJar).as("Maven must package the event producer fixture").isRegularFile();
        assertThat(consumerJar).as("Maven must package the event consumer fixture").isRegularFile();
        Path workspace = JavaItScenario.workspace();
        String projectName = "fixture-644-events";
        String happyPlan = "producer-consumer-correlation";
        String wrongKeyPlan = "producer-consumer-wrong-key";
        String crossPlanProducer = "cross-plan-producer";
        String crossPlanConsumer = "cross-plan-consumer";
        String crossPlanSession = "packaged-cross-plan-flow";
        int producerProbePort = freePort();
        int consumerProbePort = freePort();
        Path temporary = Files.createDirectories(workspace.resolve("tmp"));

        try (SpringFixtureTargetProcess consumer = SpringFixtureTargetProcess.start(
                    consumerJar,
                    List.of("-Dmcp.probe.id=" + EVENT_CONSUMER_PROBE_ID, "-Djava.io.tmpdir=" + temporary),
                    List.of());
                SpringFixtureTargetProcess producer = SpringFixtureTargetProcess.start(
                    producerJar,
                    List.of(
                            "-Dmcp.probe.id=" + EVENT_PRODUCER_PROBE_ID,
                            "-Djava.io.tmpdir=" + temporary,
                            "-Dfixture.consumer.base-url=" + LOOPBACK_HTTP_PREFIX + consumer.port()),
                    List.of())) {
            consumer.awaitHealthy();
            producer.awaitHealthy();
            Files.writeString(workspace.resolve("event-correlation.env"),
                    "EVENT_AUTHORIZATION=Bearer alice-token" + System.lineSeparator());
            try (McpServerProcess server = JvmProbeScenario.startServer(workspace)) {
                initialize(server);
                var registry = McpServerArtifactConfigIT.registry(workspace);
                var probes = (com.fasterxml.jackson.databind.node.ObjectNode) registry.path("profiles").path("local").path("probes");
                probes.removeAll();
                probes.putObject(EVENT_PRODUCER_PROBE_ID).put("baseUrl", LOOPBACK_HTTP_PREFIX + producerProbePort);
                probes.putObject(EVENT_CONSUMER_PROBE_ID).put("baseUrl", LOOPBACK_HTTP_PREFIX + consumerProbePort);
                ArtifactMcpSupport.ok(server, "probe_config.upsert", Map.of("payload", registry));
                assertThat(ArtifactMcpSupport.artifact(server, "probe_config.reload", Map.of()).path("status").asText()).isEqualTo("reloaded");
                attach(server, 90, producer, producerProbePort, EVENT_PRODUCER_INCLUDE);
                attach(server, 91, consumer, consumerProbePort, EVENT_CONSUMER_INCLUDE);
                assertThat(producer.awaitSidecarReachability(
                        producerProbePort, true, Duration.ofSeconds(15))).isTrue();
                assertThat(consumer.awaitSidecarReachability(
                        consumerProbePort, true, Duration.ofSeconds(15))).isTrue();

                Map<String, Object> projectPayload = Map.of(
                        "workspaces", List.of(Map.of(
                                "projectRoot", workspace.toString(),
                                "envFile", "event-correlation.env",
                                "variables", Map.of("contextBindings", Map.of(
                                        "authorization", "EVENT_AUTHORIZATION")),
                                "defaults", Map.of("orchestrator", Map.of(
                                        "resumePollMax", 1,
                                        "resumePollIntervalMs", 10,
                                        "resumePollTimeoutMs", 100)),
                                "executionProfiles", List.of(
                                        Map.of(
                                                "executionProfile", "event-correlation",
                                                "executionPolicy", "continue_on_fail",
                                                "suiteType", "regression",
                                                "plans", List.of(
                                                        Map.of("order", 1, "planName", happyPlan,
                                                                "onFail", "inherit"),
                                                        Map.of("order", 2, "planName", wrongKeyPlan,
                                                                "onFail", "inherit"))),
                                        Map.of(
                                                "executionProfile", "event-cross-plan",
                                                "executionPolicy", "stop_on_fail",
                                                "suiteType", "regression",
                                                "plans", List.of(
                                                        Map.of("order", 1, "planName", crossPlanProducer,
                                                                "onFail", "inherit"),
                                                        Map.of("order", 2, "planName", crossPlanConsumer,
                                                                "onFail", "inherit")))))));
                server.send(executeRequest(92, "artifact_management.project_context.upsert", Map.of(
                        "projectName", projectName,
                        "payload", projectPayload), true));
                assertThat(toolPayload(server.responseFor(92)).path("result").path("status").asText())
                        .isEqualTo("ok");
                server.send(executeRequest(192, "artifact_management.project_context.read", Map.of(
                        "projectName", projectName,
                        "query", Map.of("select", List.of("artifact"))), false));
                JsonNode persistedProject = toolPayload(server.responseFor(192));
                assertThat(persistedProject.path("result").path("details").path("artifact")
                        .path("workspaces").path(0).path("variables").path("contextBindings")
                        .path("authorization").asText()).isEqualTo("***REDACTED***");
                assertThat(JSON.readTree(workspace.resolve(".mcpjvm").resolve(projectName)
                        .resolve("projects.json").toFile()).path("workspaces").path(0)
                        .path("variables").path("contextBindings").path("authorization").asText())
                        .isEqualTo("EVENT_AUTHORIZATION");

                server.send(executeRequest(93, "artifact_management.regression_plan.upsert", Map.of(
                        "projectName", projectName,
                        "planName", happyPlan,
                        "payload", eventCorrelationPlan(producer.port(), "event-happy", null)), true));
                assertThat(toolPayload(server.responseFor(93)).path("result").path("status").asText())
                        .isEqualTo("ok");
                server.send(executeRequest(94, "artifact_management.regression_plan.upsert", Map.of(
                        "projectName", projectName,
                        "planName", wrongKeyPlan,
                        "payload", eventCorrelationPlan(
                                producer.port(), "event-wrong-key", "fixture-consumer-event-id:evt-wrong")), true));
                assertThat(toolPayload(server.responseFor(94)).path("result").path("status").asText())
                        .isEqualTo("ok");

                String happyRun = "stdio-644-event-happy-" + System.currentTimeMillis();
                server.send(executeRequest(95, "regression_suite.execute_plan", Map.of(
                        "projectName", projectName,
                        "executionProfile", "event-correlation",
                        "planName", happyPlan,
                        "suiteRunId", happyRun), true));
                JsonNode happy = toolPayload(server.responseFor(95, Duration.ofSeconds(30)));
                JsonNode happyResult = happy.path("result");
                JsonNode happyCorrelation = happyResult.path("details").path("correlation");
                assertThat(happy.path("status").asText()).isEqualTo("succeeded");
                assertThat(happyResult.path("status").asText()).as(happy.toPrettyString()).isEqualTo("ready");
                assertThat(happyResult.path("details").path("runStatus").asText()).isEqualTo("pass");
                assertThat(happyCorrelation.path("status").asText()).isEqualTo("ok");
                assertThat(happyCorrelation.path("keyValue").asText()).startsWith("evt-");
                assertThat(happyCorrelation.path("timeline"))
                        .anySatisfy(event -> assertThat(event.path("probeId").asText())
                                .isEqualTo(EVENT_PRODUCER_PROBE_ID))
                        .anySatisfy(event -> {
                            assertThat(event.path("probeId").asText()).isEqualTo(EVENT_CONSUMER_PROBE_ID);
                            assertThat(event.path("eventType").asText()).isEqualTo("runtime_line_hit");
                            assertThat(event.path("keyFingerprint").asText()).startsWith("sha256:");
                        });
                assertConsumerProcessed(consumer.port(), happyCorrelation.path("keyValue").asText());

                Path happyArtifact = workspace.resolve(".mcpjvm").resolve(projectName)
                        .resolve("plans/regression").resolve(happyPlan).resolve("runs")
                        .resolve(happyRun + "-1").resolve("correlation.json");
                assertThat(happyArtifact).isRegularFile();
                assertThat(JSON.readTree(happyArtifact.toFile()).path("status").asText()).isEqualTo("ok");
                ArtifactCorrelationAssertions.verify(server, happyArtifact, projectName, happyPlan, happyRun + "-1");

                String wrongRun = "stdio-644-event-wrong-" + System.currentTimeMillis();
                server.send(executeRequest(96, "regression_suite.execute_plan", Map.of(
                        "projectName", projectName,
                        "executionProfile", "event-correlation",
                        "planName", wrongKeyPlan,
                        "suiteRunId", wrongRun), true));
                JsonNode wrong = toolPayload(server.responseFor(96, Duration.ofSeconds(30)));
                JsonNode wrongResult = wrong.path("result");
                assertThat(wrong.path("status").asText()).isEqualTo("succeeded");
                assertThat(wrongResult.path("status").asText()).as(wrong.toPrettyString()).isEqualTo("failed");
                assertThat(wrongResult.path("reasonCode").asText()).isEqualTo("correlation_key_not_observed");
                assertThat(wrongResult.path("details").path("correlation").path("status").asText())
                        .isEqualTo("fail_closed");

                Path wrongArtifact = workspace.resolve(".mcpjvm").resolve(projectName)
                        .resolve("plans/regression").resolve(wrongKeyPlan).resolve("runs")
                        .resolve(wrongRun + "-1").resolve("correlation.json");
                assertThat(wrongArtifact).isRegularFile();
                assertThat(JSON.readTree(wrongArtifact.toFile()).path("reasonCode").asText())
                        .isEqualTo("correlation_key_not_observed");

                server.send(executeRequest(97, "artifact_management.regression_plan.upsert", Map.of(
                        "projectName", projectName,
                        "planName", crossPlanProducer,
                        "payload", eventCorrelationPlan(
                                producer.port(), crossPlanSession, null, true)), true));
                assertThat(toolPayload(server.responseFor(97)).path("result").path("status").asText())
                        .isEqualTo("ok");
                server.send(executeRequest(98, "artifact_management.regression_plan.upsert", Map.of(
                        "projectName", projectName,
                        "planName", crossPlanConsumer,
                        "payload", eventConsumerCorrelationPlan(consumer.port(), crossPlanSession)), true));
                assertThat(toolPayload(server.responseFor(98)).path("result").path("status").asText())
                        .isEqualTo("ok");

                String crossPlanRun = "stdio-644-cross-plan-" + System.currentTimeMillis();
                server.send(executeRequest(99, "execution_orchestration.execute", Map.of(
                        "projectName", projectName,
                        "executionProfile", "event-cross-plan",
                        "suiteRunId", crossPlanRun,
                        "maxPlansPerCall", 1), true));
                JsonNode firstSlice = toolPayload(server.responseFor(99, Duration.ofSeconds(45)));
                assertThat(firstSlice.path("result").path("status").asText()).isEqualTo("in_progress");
                assertThat(firstSlice.path("result").path("details").path("suiteContext")
                        .path("suite.correlation." + crossPlanSession + ".keyValue").asText())
                        .startsWith("evt-");
                server.send(executeRequest(100, "execution_orchestration.execute", Map.of(
                        "projectName", projectName,
                        "executionProfile", "event-cross-plan",
                        "suiteRunId", crossPlanRun,
                        "maxPlansPerCall", 1), true));
                JsonNode crossPlan = toolPayload(server.responseFor(100, Duration.ofSeconds(45)));
                JsonNode crossPlanResult = crossPlan.path("result");
                JsonNode crossPlanSummary = crossPlanResult.path("details").path("correlations").path(0);
                assertThat(crossPlan.path("status").asText()).isEqualTo("succeeded");
                assertThat(crossPlanResult.path("status").asText())
                        .as(crossPlan.toPrettyString()).isEqualTo("pass");
                assertThat(crossPlanSummary.path("status").asText()).isEqualTo("ok");
                assertThat(crossPlanSummary.path("reasonCode").asText()).isEqualTo("ok");
                assertThat(crossPlanSummary.path("correlationSessionId").asText())
                        .isEqualTo(crossPlanSession);
                assertThat(crossPlanSummary.path("keyValue").asText()).startsWith("evt-");
                assertThat(crossPlanSummary.path("contributingPlans"))
                        .extracting(JsonNode::asText)
                        .containsExactly(crossPlanConsumer, crossPlanProducer);
                assertThat(crossPlanResult.path("details").path("suiteContext")
                        .path("suite.correlation." + crossPlanSession + ".keyValue").asText())
                        .isEqualTo(crossPlanSummary.path("keyValue").asText());
                assertThat(crossPlanResult.path("details").path("planRuns")).hasSize(2);

                Path crossProducerArtifact = workspace.resolve(".mcpjvm").resolve(projectName)
                        .resolve("plans/regression").resolve(crossPlanProducer).resolve("runs")
                        .resolve(crossPlanRun + "-1").resolve("correlation.json");
                Path crossConsumerArtifact = workspace.resolve(".mcpjvm").resolve(projectName)
                        .resolve("plans/regression").resolve(crossPlanConsumer).resolve("runs")
                        .resolve(crossPlanRun + "-2").resolve("correlation.json");
                assertThat(crossProducerArtifact).isRegularFile();
                assertThat(crossConsumerArtifact).isRegularFile();
                assertThat(JSON.readTree(crossConsumerArtifact.toFile()).path("keyValue").asText())
                        .isEqualTo(crossPlanSummary.path("keyValue").asText());
                ArtifactCorrelationAssertions.verify(server, crossConsumerArtifact,
                        projectName, crossPlanConsumer, crossPlanRun + "-2");

                server.closeInputAndAwaitTermination();
                assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
            }
        } finally {
            deleteRecursively(workspace);
        }
    }

}
