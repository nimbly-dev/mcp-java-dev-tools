package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("orchestration")
@Timeout(180)
class McpServerPortableSidecarReplayIT extends ReplayMcpSupport {
    @ParameterizedTest
    @CsvSource({"ps1,false", "sh,false", "ps1,true", "sh,true"})
    void relocatedDynamicRuntimeUsesBundledHelperAndCleansItsOwnedJvm(String mode, boolean failScript) throws Exception {
        Path workspace = JavaItScenario.workspace();
        int targetPort = freePort();
        int probePort = freePort();
        Files.copy(repositoryRoot().resolve("test/fixtures/spring-apps/social-platform/post-service/post-app/target/"
                + "post-app-0.1.0-SNAPSHOT.jar"), workspace.resolve("fixture.jar"));
        try (McpServerProcess server = start(workspace)) {
            ObjectNode project = dynamicInputs(server, workspace, targetPort, probePort);
            if (failScript) {
                root(project).putArray("scripts").addObject().put("name", "failure").put("command", "node").put("phase", "postRuntime")
                        .putArray("args").add("-e").add("process.exit(7)");
                profile(project).putArray("scriptRefs").addObject().put("name", "failure").put("phase", "postRuntime");
                saveContext(server, project);
            }
            Path original = export(server, workspace, Map.of("projectName", PROJECT, "executionProfile", PROFILE,
                    "exportId", "dynamic", "mode", mode));
            assertThat(original.resolve("sidecar/jvm-attach-helper.jar")).isRegularFile();
            assertThat(original.resolve("sidecar/sidecar-agent.jar")).isRegularFile();
            assertThat(original.resolve("runtime/target.jar")).isRegularFile();
            Files.delete(workspace.resolve("fixture.jar"));
            Path directory = relocate(original, workspace.resolve("relocated dynamic runtime"));
            int expectedExit = 0;
            if (failScript) {
                expectedExit = mode.equals("sh") ? 7 : 1;
            }
            runPackage(directory, replayCommand(mode, directory), Map.of(), expectedExit);
            JsonNode evidence = JSON.readTree(directory.resolve("portable-runtime.evidence.json").toFile());
            assertThat(evidence.path("status").asText()).isEqualTo(failScript ? "failed" : "completed");
            assertThat(evidence.path("attachOutcome").asText()).isEqualTo("active");
            assertThat(evidence.path("probeVerification").asText()).isEqualTo("ok");
            assertThat(evidence.path("deactivateOutcome").asText()).isEqualTo("deactivated");
            assertThat(evidence.path("cleanupSucceeded").asBoolean()).isTrue();
            long pid = evidence.path("pid").asLong();
            assertThat(pid).isPositive();
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(evidence.path("processStartEpochMs").asLong()).isPositive();
            assertThat(Files.readString(directory.resolve("replay.log"))).contains(failScript ? "[S] postRuntime failure" : "replay workload");
        }
    }

    static ObjectNode dynamicInputs(McpServerProcess server, Path workspace, int targetPort, int probePort) throws Exception {
        String health = "http://127.0.0.1:" + targetPort + "/actuator/health";
        ObjectNode registry = McpServerArtifactConfigIT.registry(workspace);
        ObjectNode probes = (ObjectNode) registry.path("profiles").path("local").path("probes");
        probes.remove("two");
        probes.withObject("one").put("baseUrl", "http://127.0.0.1:" + probePort)
                .putArray("include").add("com.example.social.**");
        ok(server, "probe_config.upsert", Map.of("payload", registry));
        ObjectNode project = context(workspace, health, "health");
        profile(project).put("runtimeContextName", "owned");
        ObjectNode runtime = root(project).putArray("runtimeContexts").addObject().put("name", "owned")
                .put("mode", "terminal").put("autoStart", true).put("autoStopOnFinish", true);
        runtime.putObject("sidecarLifecycle").put("activation", "dynamic_attach_local").put("probeId", "one")
                .put("targetStartupName", "fixture").put("verifyProbeAfterAttach", true);
        runtime.putArray("startups").addObject().put("name", "fixture").put("command", "java").put("appdir", ".")
                .putArray("args").add("-jar").add("fixture.jar").add("--server.address=127.0.0.1").add("--server.port=" + targetPort);
        ObjectNode system = root(project).putArray("externalSystems").addObject().put("name", "fixture")
                .put("kind", "http").put("host", "127.0.0.1").put("port", targetPort);
        system.putArray("healthChecks").addObject().put("id", "up").put("type", "http").put("url", health).put("required", true);
        saveContext(server, project);
        save(server, "health", generatedPlan(health));
        return project;
    }
}
