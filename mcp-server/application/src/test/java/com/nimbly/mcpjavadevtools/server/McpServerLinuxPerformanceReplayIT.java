package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Native Linux evidence, including a real signal delivered during owned profiling/workload. */
@Tag("orchestration")
@Tag("orchestration-docker")
@Timeout(240)
class McpServerLinuxPerformanceReplayIT extends ReplayMcpSupport {
    @ParameterizedTest
    @CsvSource({"builtin,false", "jmeter,false", "builtin,true", "jmeter,true"})
    void nativeLinuxReplaysAndCancelsOwnedPerformanceWork(String provider, boolean cancel) throws Exception {
        Path workspace = JavaItScenario.workspace();
        String owner = "java-it-655-" + UUID.randomUUID();
        String docker = executable("mcpItDocker", "docker");
        try (McpServerProcess server = start(workspace)) {
            Path directory = packageInputs(server, workspace, provider, cancel);
            Files.writeString(directory.resolve("Dockerfile"), "FROM node:22-bookworm-slim AS node\n"
                    + "FROM maven:3.9.12-eclipse-temurin-21\nCOPY --from=node /usr/local/bin/node /usr/local/bin/node\n");
            try {
                runPackage(directory, List.of(docker, "build", "-t", owner, "."), Map.of());
                startContainer(directory, docker, owner);
                if (cancel) {
                    interruptActualWork(directory, docker, owner);
                }
                runPackage(directory, List.of(docker, "wait", owner), Map.of());
                assertThat(Files.readString(directory.resolve("replay.log")).trim()).isEqualTo(cancel ? "1" : "0");
                assertEvidence(directory, cancel);
                JavaItScenario.observe("execution_profile_export.export", "ASSERTED", "linux_" + provider + "_cancel_" + cancel);
            } finally {
                cleanupContainerAndImage(directory, docker, owner);
            }
        }
    }

    private void cleanupContainerAndImage(Path directory, String docker, String owner) throws Exception {
        try {
            runPackage(directory, List.of(docker, "ps", "-a", "--filter", "name=^/" + owner + "$", "--format", "{{.ID}}"), Map.of());
            if (!Files.readString(directory.resolve("replay.log")).isBlank()) {
                runPackage(directory, List.of(docker, "rm", "-f", owner), Map.of());
            }
            runPackage(directory, List.of(docker, "ps", "-a", "--filter", "name=^/" + owner + "$", "--format", "{{.ID}}"), Map.of());
            assertThat(Files.readString(directory.resolve("replay.log")).trim()).isEmpty();
        } finally {
            runPackage(directory, List.of(docker, "image", "ls", owner, "--format", "{{.ID}}"), Map.of());
            if (!Files.readString(directory.resolve("replay.log")).isBlank()) {
                runPackage(directory, List.of(docker, "image", "rm", owner), Map.of());
            }
        }
    }

    private Path packageInputs(McpServerProcess server, Path workspace, String provider, boolean cancel) throws Exception {
        int targetPort = freePort();
        int probePort = freePort();
        Files.copy(repositoryRoot().resolve("test/fixtures/spring-apps/social-platform/post-service/post-app/target/"
                + "post-app-0.1.0-SNAPSHOT.jar"), workspace.resolve("fixture.jar"));
        ObjectNode project = McpServerPortableSidecarReplayIT.dynamicInputs(server, workspace, targetPort, probePort);
        profile(project).put("suiteType", "performance");
        root(project).put("envFile", "fixture.env").putObject("variables").putObject("contextBindings").put("Authorization", "REPLAY_AUTH");
        Files.writeString(workspace.resolve("fixture.env"), "REPLAY_AUTH=Bearer alice-token\n");
        saveContext(server, project);
        ObjectNode payload = plan("performance", "http://127.0.0.1:" + targetPort);
        ObjectNode contract = contract(payload);
        contract.putObject("workloadProvider").put("type", provider);
        if (provider.equals("jmeter")) {
            contract.withObject("workloadProvider").put("mode", "generated_http");
        }
        contract.withObject("loadModel").put("durationSeconds", cancel ? 20 : 2);
        contract.withObject("successCriteria").put("minThroughputPerSec", 0.000001).put("p95LatencyMs", 300000);
        PerformanceMcpSupport.entry(payload).withObject("request").put("path", "/api/v1/posts/101").putObject("headers")
                .put("Authorization", "${Authorization}").put("x-run-as-user", "alice").put("x-run-as-tenant", "fixture-tenant");
        contract.putObject("observationTargets").put("probeId", "one").putArray("requiredLineHits")
                .add(JvmProbeScenario.key(JvmProbeScenario.CONTROLLER, "getPost", "return postService.getPost(postId);"));
        contract.putObject("analysis").putObject("executionTiming").put("enabled", true)
                .put("provider", "jfr").put("event", "cpu").put("outputFormat", "jfr");
        ok(server, "performance_plan.upsert", Map.of("projectName", PROJECT, "planName", "health", "payload", payload));
        Path original = export(server, workspace, Map.of("projectName", PROJECT, "executionProfile", PROFILE,
                "exportId", "linux", "mode", "sh"));
        Path directory = relocate(original, workspace.resolve("native linux package"));
        Files.writeString(directory.resolve("project.env"), "REPLAY_AUTH=\"Bearer alice-token\"\n");
        // Test instrumentation records a real child PID; it does not alter generated code or results.
        Files.writeString(directory.resolve("observer.cjs"), "if(process.argv[1]?.endsWith('/run-performance-profile.js'))"
                + "require('node:fs').writeFileSync('/replay/runner.pid',String(process.pid));\n");
        return directory;
    }

    private void startContainer(Path directory, String docker, String owner) throws Exception {
        Path jmeter = jmeterHome();
        runPackage(directory, List.of(docker, "run", "-d", "--name", owner, "--label", "mcpjvm.it.owner=" + owner,
                "-v", directory + ":/replay", "-v", jmeter + ":/jmeter:ro", "-w", "/replay",
                "-e", "MCP_JAVA_DEV_TOOLS_JMETER_HOME=/jmeter", "-e", "NODE_OPTIONS=--require=/replay/observer.cjs",
                owner, "bash", "-c", "bash run-execution-profile.sh > native-replay.log 2>&1"), Map.of());
    }

    private void interruptActualWork(Path directory, String docker, String owner) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(65);
        Path pid = directory.resolve("runner.pid");
        while (!Files.exists(pid) && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(pid).isRegularFile();
        JsonNode config = JSON.readTree(directory.resolve("portable-sidecar-attach.config.json").toFile());
        String endpoint = "http://127.0.0.1:" + config.path("probePort").asInt() + "/__probe/profiler";
        String lineKey = JvmProbeScenario.key(JvmProbeScenario.CONTROLLER, "getPost", "return postService.getPost(postId);");
        String hitEndpoint = "http://127.0.0.1:" + config.path("probePort").asInt() + "/__probe/status?key="
                + java.net.URLEncoder.encode(lineKey, java.nio.charset.StandardCharsets.UTF_8);
        String observer = "const fs=require('node:fs');(async()=>{for(let i=0;i<100;i++){"
                + "let r=await fetch('" + endpoint + "').then(r=>r.json()).catch(()=>({}));"
                + "let h=await fetch('" + hitEndpoint + "').then(r=>r.json()).catch(()=>({}));"
                + "if((r.profiler??r).status==='running'&&(h.probe??h).hitCount>0){"
                + "fs.writeFileSync('/replay/profiler-running.json',JSON.stringify({profiler:r,probe:h}));"
                + "process.kill(Number(fs.readFileSync('/replay/runner.pid','utf8')),'SIGTERM');return;}"
                + "await new Promise(r=>setTimeout(r,100));}process.exitCode=1;})();";
        runPackage(directory, List.of(docker, "exec", owner, "node", "-e", observer), Map.of());
        assertThat(directory.resolve("profiler-running.json")).isRegularFile();
    }

    private void assertEvidence(Path directory, boolean cancelled) throws Exception {
        JsonNode lifecycle = JSON.readTree(directory.resolve("portable-runtime.evidence.json").toFile());
        assertThat(lifecycle.path("attachOutcome").asText()).isEqualTo("active");
        assertThat(lifecycle.path("deactivateOutcome").asText()).isEqualTo("deactivated");
        assertThat(lifecycle.path("cleanupSucceeded").asBoolean()).isTrue();
        assertThat(lifecycle.path("status").asText()).isEqualTo(cancelled ? "failed" : "completed");
        try (var paths = Files.walk(directory.resolve("runs"))) {
            Path result = paths.filter(p -> p.getFileName().toString().equals("health.execution.result.json")).findFirst().orElseThrow();
            JsonNode actual = JSON.readTree(result.toFile());
            assertThat(actual.path("runStatus").asText()).as(actual.toPrettyString()).isEqualTo(cancelled ? "blocked" : "pass");
            assertThat(actual.path("executionTiming").path("stop").path("status").asText()).isEqualTo("completed");
            assertThat(Files.size(result.getParent().resolve("health.execution-timing.jfr"))).isGreaterThan(68);
            if (cancelled) {
                assertThat(actual.path("reasonCode").asText()).isEqualTo("performance_workload_interrupted");
            } else {
                assertThat(actual.path("metrics").path("totalRequests").asLong()).isGreaterThan(1);
                assertThat(actual.path("requiredLineHits").get(0).path("hit").asBoolean()).isTrue();
            }
        }
    }
}
