package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("regression")
@Timeout(240)
class McpServerRegressionCorrelationBoundaryIT extends RegressionMcpSupport {
    @ParameterizedTest
    @ValueSource(strings = {"traceId", "requestId", "messageId"})
    void responseCorrelationWithoutRequiredRuntimeEvidencePreservesEverySupportedKeyType(String keyType) throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(""), "response"));
            ObjectNode plan = generatedPlan(target.url("/response"));
            correlate(plan, "$.eventId", null);
            ObjectNode correlation = (ObjectNode) contract(plan).path("correlation");
            ((ObjectNode) correlation.path("key")).put("type", keyType).putObject("source")
                    .put("type", "json_path").put("path", "response.bodyJson.safe");
            ((ObjectNode) correlation.path("runtimeEvidence")).put("required", false);
            save(server, "response", plan);
            JsonNode result = run(server, "response", keyType);
            assertThat(result.path("details").path("runStatus").asText()).as(result.toPrettyString()).isEqualTo("pass");
            JsonNode evidence = result.path("details").path("correlation");
            assertThat(evidence.path("keyType").asText()).isEqualTo(keyType);
            assertThat(evidence.path("keyValue").asText()).isEqualTo("observed");
            assertThat(evidence.path("timeline")).isNotEmpty();
            assertRun(server, workspace, "response", keyType, "pass");
            assertThat(target.received().uri()).isEqualTo("/response");
        }
    }

    @Test
    void kclConsistentPartitionCorrelatesAndMixedPartitionsFailClosed() throws Exception {
        try (EventScenario s = EventScenario.start("event-consumer-app", "com.example.social.event.consumer.app.**")) {
            s.project("consistent", "mixed");
            ObjectNode plan = generatedPlan(s.url + "/internal/events/kcl");
            http(step(plan, 0)).put("method", "POST").putObject("headers").put("Authorization", "${authorization}");
            http(step(plan, 0)).putObject("body").putArray("records").addObject()
                    .put("eventId", "kcl-consistent").put("partitionKey", "tenant-kcl");
            correlate(plan, "$.kcl.partitionKey", "tenant-kcl");
            save(s.server, "consistent", plan);
            JsonNode pass = run(s.server, "consistent", "kcl");
            assertThat(pass.path("details").path("runStatus").asText()).as(pass.toPrettyString()).isEqualTo("pass");
            String key = sourceKey("event-consumer-app", "com.example.social.event.consumer.app.service.KclFixtureShardRecordProcessor",
                    "processRecords", "processingStore.markProcessed(");
            assertRuntimeLine(pass, key);
            assertRun(s.server, s.workspace, "consistent", "kcl", "pass");
            ((ObjectNode) http(step(plan, 0)).path("body")).withArray("records").addObject()
                    .put("eventId", "kcl-mixed").put("partitionKey", "other-partition");
            save(s.server, "mixed", plan);
            JsonNode failed = run(s.server, "mixed", "mixed");
            assertThat(failed.path("status").asText()).isEqualTo("failed");
            assertThat(failed.path("details").path("correlation").path("status").asText()).isEqualTo("fail_closed");
            assertRun(s.server, s.workspace, "mixed", "mixed", "fail");
        }
    }

    @Test
    void jdkExecutorHandoffCarriesCorrelationIntoTheActualProcessor() throws Exception {
        try (EventScenario s = EventScenario.start("event-app", "com.example.social.event.app.**")) {
            s.project("async");
            ObjectNode plan = generatedPlan(s.url + "/api/v1/events/trigger");
            http(step(plan, 0)).put("method", "POST").putObject("headers").put("Authorization", "${authorization}");
            ObjectNode body = http(step(plan, 0)).putObject("body").put("context", "entities").put("type", "TriggerIndex")
                    .put("groupId", "group-001").put("source", "regression-it").put("dataFormatVersion", 1)
                    .put("dataId", "batch-001").put("notes", "generic-jdk-correlation");
            body.putArray("data").add("fixture-tenant");
            correlate(plan, "$.eventId", null);
            ObjectNode correlation = (ObjectNode) contract(plan).path("correlation");
            ObjectNode boundary = correlation.putArray("consumerBoundaries").addObject().put("id", "async-listener")
                    .put("eventArgumentIndex", 0);
            boundary.putObject("selector").put("fqcn", "com.example.social.event.app.listener.AsyncPropagationListener")
                    .put("method", "receiveForAsyncPropagation").putArray("parameterTypes")
                    .add("com.example.social.event.app.model.IndexRequestedEvent");
            save(s.server, "async", plan);
            JsonNode pass = run(s.server, "async", "jdk-executor");
            assertThat(pass.path("details").path("runStatus").asText()).as(pass.toPrettyString()).isEqualTo("pass");
            String key = sourceKey("event-app", "com.example.social.event.app.service.AsyncPropagationProcessor",
                    "process", "processingStore.markProcessed(");
            assertRuntimeLine(pass, key);
            assertRun(s.server, s.workspace, "async", "jdk-executor", "pass");
        }
    }

    private static void correlate(ObjectNode plan, String eventKey, String key) {
        ((ObjectNode) contract(plan).path("targets").get(0)).putObject("runtimeVerification").put("probeId", "one");
        step(plan, 0).put("targetRef", 0);
        ObjectNode correlation = contract(plan).putObject("correlation").put("enabled", true)
                .put("crossPlan", false).put("correlationSessionId", "boundary");
        ObjectNode selector = correlation.putObject("key").put("type", "messageId");
        if (key != null) {
            selector.put("value", key);
        } else {
            selector.putObject("source").put("type", "json_path").put("path", "response.bodyJson.eventId");
        }
        correlation.putObject("window").put("maxWindowMs", 10000);
        correlation.putArray("probeIds").add("one");
        correlation.putArray("expectedFlow").add("one");
        correlation.putObject("runtimeEvidence").put("required", true).put("eventKeyPath", eventKey)
                .put("maxDurationMs", 2000).put("pageLimit", 100).put("maxEvents", 256).putArray("probeIds").add("one");
    }

    private static String sourceKey(String module, String fqcn, String method, String snippet) throws Exception {
        Path source = repositoryRoot().resolve("test/fixtures/spring-apps/social-platform/event-service/" + module
                + "/src/main/java/" + fqcn.replace('.', '/') + ".java");
        List<String> lines = Files.readAllLines(source);
        List<Integer> matches = java.util.stream.IntStream.range(0, lines.size())
                .filter(index -> lines.get(index).contains(snippet)).boxed().toList();
        assertThat(matches).hasSize(1);
        return fqcn + "#" + method + ":" + (matches.getFirst() + 1);
    }

    private static void assertRuntimeLine(JsonNode result, String key) {
        JsonNode correlation = result.path("details").path("correlation");
        assertThat(correlation.path("status").asText()).isEqualTo("ok");
        assertThat(correlation.path("timeline")).anySatisfy(event -> {
            assertThat(event.path("lineKey").asText()).isEqualTo(key);
            assertThat(event.path("eventType").asText()).isEqualTo("runtime_line_hit");
            assertThat(event.path("keyFingerprint").asText()).startsWith("sha256:");
            assertThat(event.path("runtimeInstanceId").asText()).isNotBlank();
        });
    }

    private static class EventScenario implements AutoCloseable {
        private final Path workspace;
        private final SpringFixtureTargetProcess target;
        private final McpServerProcess server;
        private final String url;

        private EventScenario(Path workspace, SpringFixtureTargetProcess target, McpServerProcess server) {
            this.workspace = workspace;
            this.target = target;
            this.server = server;
            this.url = "http://127.0.0.1:" + target.port();
        }

        void project(String... plans) throws Exception {
            Files.writeString(workspace.resolve("fixture.env"), "FIXTURE_AUTH=Bearer alice-token\n");
            ObjectNode project = context(workspace, url, plans);
            ObjectNode root = (ObjectNode) project.path("workspaces").get(0);
            root.put("envFile", "fixture.env").putObject("variables").putObject("contextBindings")
                    .put("authorization", "FIXTURE_AUTH");
            saveContext(server, project);
        }

        static EventScenario start(String module, String include) throws Exception {
            Path workspace = JavaItScenario.workspace();
            Path temporary = Files.createDirectories(workspace.resolve("tmp"));
            Path fixture = repositoryRoot().resolve("test/fixtures/spring-apps/social-platform/event-service/" + module
                    + "/target/" + module + "-0.1.0-SNAPSHOT.jar");
            assertThat(fixture).as("Build the Regression fixture selection").isRegularFile();
            SpringFixtureTargetProcess target = SpringFixtureTargetProcess.start(fixture,
                    List.of("-Dmcp.probe.id=one", "-Djava.io.tmpdir=" + temporary), List.of());
            try {
                target.awaitHealthy();
                McpServerProcess server = JvmProbeScenario.startServer(workspace);
                try {
                    initialize(server);
                    int port = freePort();
                    attach(server, 90, target, port, include);
                    ObjectNode registry = McpServerArtifactConfigIT.registry(workspace);
                    ((ObjectNode) registry.path("profiles").path("local").path("probes").path("one"))
                            .put("baseUrl", "http://127.0.0.1:" + port);
                    ok(server, "probe_config.upsert", Map.of("payload", registry));
                    artifact(server, "probe_config.reload", Map.of());
                    return new EventScenario(workspace, target, server);
                } catch (Throwable failure) {
                    server.close();
                    throw failure;
                }
            } catch (Throwable failure) {
                target.close();
                throw failure;
            }
        }

        @Override
        public void close() throws Exception {
            try {
                JsonNode deactivated = execute(server, "jvm_lifecycle.deactivate", Map.of("pid", Long.toString(target.pid()),
                        "expectedProcessStartEpochMs", target.processStartEpochMs()), true);
                assertThat(deactivated.path("result").path("reasonCode").asText()).isEqualTo("deactivated");
            } finally {
                try {
                    server.close();
                } finally {
                    target.close();
                }
            }
        }
    }
}
