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

@Tag("orchestration")
@Timeout(240)
class McpServerOrchestrationLifecycleIT extends OrchestrationMcpSupport {
    @Test
    void mcpOwnsStartupAttachHealthRestartAndTerminalCleanup() throws Exception {
        Path workspace = JavaItScenario.workspace();
        int targetPort = freePort();
        int probePort = freePort();
        Path fixture = repositoryRoot().resolve("test/fixtures/spring-apps/social-platform/post-service/post-app/target/post-app-0.1.0-SNAPSHOT.jar");
        assertThat(fixture).as("post-app fixture prerequisite").isRegularFile();
        Files.copy(fixture, workspace.resolve("fixture.jar"));
        Files.createDirectories(workspace.resolve("tmp"));
        String health = "http://127.0.0.1:" + targetPort + "/actuator/health";
        try (TransportHttpTarget trigger = TransportHttpTarget.start()) {
            long pid;
            try (McpServerProcess server = JvmProbeScenario.startServer(workspace)) {
                initialize(server);
                lifecycleInputs(server, workspace, trigger.url("/started"), health, targetPort, probePort);
                JsonNode first = orchestrate(server, PROFILE, "lifecycle", 1);
                JsonNode lifecycle = first.path("details").path("runtimeLifecycle");
                pid = lifecycle.path("pid").asLong();
                if (pid > 0) {
                    ProcessHandle.of(pid).ifPresent(JavaItScenario::own);
                }
                assertThat(lifecycle.path("probeVerification").asText()).as(first.toPrettyString()).isEqualTo("ok");
                assertThat(lifecycle.path("attachOutcome").asText()).isEqualTo("active");
                assertThat(ProcessHandle.of(pid).orElseThrow().isAlive()).isTrue();
                awaitHealth(health);
                checkpoint(workspace, first);
            }
            try (McpServerProcess server = JvmProbeScenario.startServer(workspace)) {
                initialize(server);
                JsonNode result = orchestrate(server, PROFILE, "lifecycle", 1);
                for (int attempt = 0; attempt < 25 && result.path("status").asText().equals("in_progress"); attempt++) {
                    Thread.sleep(200);
                    result = orchestrate(server, PROFILE, "lifecycle", 1);
                }
                assertStatus(result, "pass", "ok");
                assertThat(result.path("details").path("runtimeLifecycle").path("cleanupStatus").asText()).isEqualTo("ok");
                assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
                assertThat(trigger.received().uri()).isEqualTo("/started");
                trigger.assertNoRequest();
                checkpoint(workspace, result);
                assertStatus(orchestrate(server, PROFILE, "lifecycle", 0), "pass", "ok");
            }
        }
    }

    private void lifecycleInputs(McpServerProcess server, Path workspace, String trigger, String health,
            int targetPort, int probePort) throws Exception {
        ObjectNode registry = McpServerArtifactConfigIT.registry(workspace);
        ObjectNode probes = (ObjectNode) registry.path("profiles").path("local").path("probes");
        probes.remove("two");
        probes.withObject("one").put("baseUrl", "http://127.0.0.1:" + probePort)
                .putArray("include").add("com.example.social.**");
        ok(server, "probe_config.upsert", Map.of("payload", registry));
        ObjectNode project = context(workspace, health, "startup", "health");
        ObjectNode runtime = root(project).putArray("runtimeContexts").addObject().put("name", "owned")
                .put("mode", "terminal").put("autoStart", true).put("autoStopOnFinish", true);
        runtime.putObject("sidecarLifecycle").put("activation", "dynamic_attach_local").put("probeId", "one")
                .put("targetStartupName", "fixture").put("verifyProbeAfterAttach", true);
        ObjectNode startup = runtime.putArray("startups").addObject().put("name", "fixture").put("command", "java").put("appdir", ".");
        startup.set("args", JSON.valueToTree(List.of("-Djava.io.tmpdir=" + workspace.resolve("tmp"), "-jar", "fixture.jar",
                "--server.address=127.0.0.1", "--server.port=" + targetPort, "--spring.main.banner-mode=off")));
        profile(project).put("runtimeContextName", "owned");
        saveContext(server, project);
        save(server, "startup", generatedPlan(trigger));
        save(server, "health", generatedPlan(health));
    }

    private void awaitHealth(String url) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            while (System.nanoTime() < deadline) {
                try {
                    var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                            .timeout(java.time.Duration.ofSeconds(2)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200 && JSON.readTree(response.body()).path("status").asText().equals("UP")) {
                        return;
                    }
                } catch (java.io.IOException starting) {
                    // Readiness is independently observed; no successful fixture response is manufactured.
                }
                Thread.sleep(200);
            }
        }
        throw new AssertionError("MCP-owned fixture did not become healthy within 30 seconds");
    }
}
