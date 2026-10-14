package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("security")
@Timeout(150)
class McpServerSecurityRecoveryIT extends SecurityMcpSupport {
    @Test
    void refreshDeadlineStopsItsActualDescendantTreeBeforeReturning() throws Exception {
        Path workspace = JavaItScenario.workspace();
        Path helper = workspace.resolve("helper/com/nimbly/mcpjavadevtools/server/PerformanceStalledProcess.class");
        Files.createDirectories(helper.getParent());
        Files.copy(testClassesPath().resolve("com/nimbly/mcpjavadevtools/server/PerformanceStalledProcess.class"), helper);
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path script = workspace.resolve(windows ? "stalled.ps1" : "stalled.sh");
        String executable = "'" + McpServerProcess.javaBinary().replace("'", windows ? "''" : "'\"'\"'") + "'";
        Files.writeString(script, (windows ? "& " : "exec ") + executable
                + " -cp ./helper com.nimbly.mcpjavadevtools.server.PerformanceStalledProcess refresh-pids.txt\n");
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            ObjectNode project = context(workspace, "refresh-timeout");
            ObjectNode root = (ObjectNode) project.path("workspaces").get(0);
            ObjectNode refresh = root.putArray("scripts").addObject().put("name", "refresh").put("phase", "prePlan")
                    .put("command", windows ? "ps" : "sh");
            if (windows) {
                refresh.putArray("args").add("-NoProfile").add("-NonInteractive").add("-ExecutionPolicy").add("Bypass")
                        .add("-File").add("./stalled.ps1");
            } else {
                refresh.putArray("args").add("./stalled.sh");
            }
            ((ObjectNode) root.path("executionProfiles").get(0)).putArray("scriptRefs").addObject()
                    .put("name", "refresh").put("phase", "prePlan");
            saveContext(server, project);
            save(server, "refresh-timeout", generatedPlan(target.url("")));
            server.send(executeRequest(920, OPERATION, selector("refresh-timeout", "timeout"), true));
            Path marker = workspace.resolve("refresh-pids.txt");
            awaitFile(marker, Duration.ofSeconds(10));
            List<ProcessHandle> children = Files.readAllLines(marker).stream().map(Long::parseLong)
                    .map(ProcessHandle::of).flatMap(java.util.Optional::stream).toList();
            assertThat(children).hasSize(2);
            children.forEach(JavaItScenario::own);
            JsonNode result = toolPayload(server.responseFor(920, Duration.ofSeconds(30))).path("result");
            assertBlocked(result, "security_credential_refresh_failed");
            target.assertNoRequest();
            assertThat(children).as("refresh failure must not retain live subprocesses")
                    .allSatisfy(child -> assertThat(child.isAlive()).isFalse());
            assertRun(server, workspace, "refresh-timeout", "timeout", "blocked");
        }
    }

    @Test
    void cancelledMcpWaitCompletesBoundedRunAndRestartReusesTheActualResult() throws Exception {
        Path workspace = JavaItScenario.workspace();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new java.io.IOException("scenario release deadline exceeded");
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("fixture interrupted");
            }
            TransportHttpTarget.respond(exchange, 200, "{}");
        })) {
            JsonNode persisted;
            try (McpServerProcess server = start(workspace)) {
                saveContext(server, context(workspace, "cancel"));
                ObjectNode plan = generatedPlan(target.url(""));
                packs(plan, "web-api-core");
                save(server, "cancel", plan);
                server.send(executeRequest(900, OPERATION, selector("cancel", "cancel"), true));
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                server.send(executeRequest(901, OPERATION, selector("cancel", "cancel"), true));
                JsonNode concurrent = toolPayload(server.responseFor(901, Duration.ofSeconds(5))).path("result");
                assertBlocked(concurrent, "direct_suite_run_in_progress");
                server.send(Map.of("jsonrpc", "2.0", "method", "notifications/cancelled", "params", Map.of("requestId", 900)));
                release.countDown();
                awaitFile(runFile(workspace, "cancel", "cancel"), Duration.ofSeconds(20));
                persisted = assertRun(server, workspace, "cancel", "cancel", "pass");
                assertThat(target.received().uri()).isEqualTo("/safe");
                target.assertNoRequest();
                assertThat(server.hasResponseFor(900)).isFalse();
            } finally {
                release.countDown();
            }
            try (McpServerProcess restarted = start(workspace)) {
                JsonNode recovered = run(restarted, "cancel", "cancel");
                assertThat(recovered).isEqualTo(persisted.path("directResult"));
                target.assertNoRequest();
                assertRun(restarted, workspace, "cancel", "cancel", "pass");
            }
        }
    }

    @Test
    void actualTransportDeadlineAndConnectionFailurePersistBlockedAndNewRunRecovers() throws Exception {
        Path workspace = JavaItScenario.workspace();
        CountDownLatch release = new CountDownLatch(1);
        try (TransportHttpTarget slow = TransportHttpTarget.start(exchange -> {
            try {
                release.await(25, TimeUnit.SECONDS);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            }
            TransportHttpTarget.respond(exchange, 200, "{}");
        }); TransportHttpTarget healthy = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "timeout", "unavailable"));
            ObjectNode plan = generatedPlan(slow.url(""));
            packs(plan, "web-api-core");
            save(server, "timeout", plan);
            long started = System.nanoTime();
            assertBlocked(run(server, "timeout", "timeout"), "transport_request_timeout");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
            assertThat(slow.received().uri()).isEqualTo("/safe");
            release.countDown();
            assertRun(server, workspace, "timeout", "timeout", "blocked");
            save(server, "timeout", generatedPlan(healthy.url("")));
            completed(run(server, "timeout", "recovered"), "pass", 10);
            assertThat(healthy.received().uri()).isEqualTo("/safe");
            healthy.assertNoRequest();
            ObjectNode unavailable = generatedPlan("http://127.0.0.1:" + freePort());
            save(server, "unavailable", unavailable);
            JsonNode failed = run(server, "unavailable", "unavailable");
            assertThat(failed.path("status").asText()).isEqualTo("blocked");
            assertThat(failed.path("reasonCode").asText()).isEqualTo("transport_request_failed");
            assertRun(server, workspace, "unavailable", "unavailable", "blocked");
        } finally {
            release.countDown();
        }
    }

    private void awaitFile(Path file, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (!Files.isRegularFile(file) && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        assertThat(file).isRegularFile();
    }
}
