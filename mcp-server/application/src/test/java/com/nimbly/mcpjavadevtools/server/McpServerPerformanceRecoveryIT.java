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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("performance")
@Timeout(240)
class McpServerPerformanceRecoveryIT extends PerformanceMcpSupport {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 7})
    void launcherExitStillCleansItsLivingChildBeforeReturning(int exitCode) throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s, "orphan");
            Path marker = s.workspace.resolve("orphan-pids.txt");
            Path launcher = stalledLauncher(s.workspace, marker, " exit " + exitCode);
            ObjectNode plan = workload(s);
            contract(plan).withObject("workloadProvider").withObject("options").put("installationPath", launcher.toString());
            contract(plan).putObject("analysis").putObject("executionTiming").put("enabled", true);
            save(s, "orphan", plan);
            s.server.send(executeRequest(702, "performance_suite.execute_plan", selector("orphan", "orphan"), true));
            awaitFile(marker, Duration.ofSeconds(15));
            List<Long> pids = Files.readAllLines(marker).stream().map(Long::parseLong).toList();
            List<ProcessHandle> children = pids.stream().map(ProcessHandle::of).flatMap(java.util.Optional::stream).toList();
            children.forEach(JavaItScenario::own);
            JsonNode result = toolPayload(s.server.responseFor(702, Duration.ofSeconds(30))).path("result");
            assertThat(result.path("reasonCode").asText()).isEqualTo(exitCode == 0
                    ? "performance_jmeter_results_missing" : "performance_jmeter_execution_failed");
            assertThat(pids).allSatisfy(pid -> assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse());
            assertRun(s, "orphan", "orphan", "blocked");
            assertThat(s.success("profiler", Map.of("action", "status")).path("status").asText()).isEqualTo("completed");
            JavaItScenario.observe("performance_suite.execute_plan", "ASSERTED", "launcher-exit:" + exitCode);
            s.success("profiler", Map.of("action", "reset"));
            s.deactivate();
        }
    }

    @Test
    void slicedMultiPlanExecutionResumesAfterServerRestartWithoutRepeatingTheFirstWorkload() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s, "first", "second");
            save(s, "first", workload(s));
            save(s, "second", workload(s));
            Map<String, Object> selector = Map.of("projectName", PROJECT, "executionProfile", PROFILE,
                    "suiteRunId", "sliced", "maxPlansPerCall", 1);
            JsonNode first = s.result("execution_orchestration.execute", selector, true);
            assertThat(first.path("status").asText()).as(first.toPrettyString()).isEqualTo("in_progress");
            assertThat(first.path("details").path("nextPlanOrder").asInt()).isEqualTo(2);
            assertThat(first.path("details").path("planRuns")).hasSize(1);
            Path firstJtl = runDirectory(s, "first", "sliced").resolve("workload.jmeter.jtl");
            byte[] original = Files.readAllBytes(firstJtl);
            var modified = Files.getLastModifiedTime(firstJtl);
            s.server.close();
            try (McpServerProcess restarted = JvmProbeScenario.startServer(s.workspace)) {
                initialize(restarted);
                JsonNode resumed = execute(restarted, "execution_orchestration.execute", selector, true).path("result");
                assertThat(resumed.path("status").asText()).as(resumed.toPrettyString()).isEqualTo("pass");
                assertThat(resumed.path("details").path("planRuns")).hasSize(2);
                assertThat(resumed.path("details").path("planRuns")).allSatisfy(row ->
                        assertThat(row.path("details").path("runStatus").asText()).isEqualTo("pass"));
                assertThat(Files.readAllBytes(firstJtl)).isEqualTo(original);
                assertThat(Files.getLastModifiedTime(firstJtl)).isEqualTo(modified);
                assertThat(s.workspace.resolve(".mcpjvm/" + PROJECT
                        + "/plans/performance/second/runs/sliced-2/workload.jmeter.jtl")).isRegularFile();
                assertThat(execute(restarted, "execution_orchestration.execute", selector, true).path("result")
                        .path("status").asText()).isEqualTo("pass");
            }
        }
    }

    @Test
    void subprocessFailureStopsOwnedProfilerAndPersistsSanitizedBlockedResult() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s, "failed");
            ObjectNode plan = workload(s);
            contract(plan).withObject("workloadProvider").withObject("options")
                    .put("installationPath", McpServerProcess.javaBinary());
            contract(plan).putObject("analysis").putObject("profiler").put("enabled", true);
            save(s, "failed", plan);
            assertBlocked(s, "failed", "performance_jmeter_execution_failed");
            assertThat(s.success("profiler", Map.of("action", "status")).path("status").asText()).isEqualTo("completed");
            JsonNode persisted = assertRun(s, "failed", "failed", "blocked");
            assertThat(persisted.toString()).doesNotContain("Unrecognized option", "Exception", "alice-token");
            s.success("profiler", Map.of("action", "reset"));
            s.deactivate();
        }
    }

    @Test
    void workloadDeadlineKillsTheEntireStalledSubprocessTree() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s, "timeout");
            Path marker = s.workspace.resolve("stalled-pids.txt");
            Path launcher = stalledLauncher(s.workspace, marker);
            ObjectNode plan = workload(s);
            contract(plan).withObject("loadModel").put("durationSeconds", 1);
            contract(plan).withObject("workloadProvider").withObject("options").put("installationPath", launcher.toString());
            contract(plan).putObject("analysis").putObject("executionTiming").put("enabled", true);
            save(s, "timeout", plan);
            s.server.send(executeRequest(700, "performance_suite.execute_plan", selector("timeout", "timeout"), true));
            awaitFile(marker, Duration.ofSeconds(15));
            List<ProcessHandle> children = Files.readAllLines(marker).stream().map(Long::parseLong)
                    .map(pid -> ProcessHandle.of(pid).orElseThrow()).toList();
            children.forEach(JavaItScenario::own);
            assertThat(children).allSatisfy(child -> assertThat(child.isAlive()).isTrue());
            JsonNode result = toolPayload(s.server.responseFor(700, Duration.ofSeconds(50))).path("result");
            assertThat(result.path("reasonCode").asText()).as(result.toPrettyString()).isEqualTo("performance_jmeter_execution_failed");
            assertThat(children).as("production deadline must clean descendants before reporting completion")
                    .allSatisfy(child -> assertThat(child.isAlive()).isFalse());
            assertRun(s, "timeout", "timeout", "blocked");
            assertThat(s.success("profiler", Map.of("action", "status")).path("status").asText()).isEqualTo("completed");
            s.success("profiler", Map.of("action", "reset"));
            s.deactivate();
        }
    }

    @Test
    void cancelledMcpWaitRetainsBoundedNonCancellableExecutionAndRecoverableResult() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s, "cancel");
            ObjectNode plan = workload(s);
            contract(plan).withObject("loadModel").put("durationSeconds", 6);
            save(s, "cancel", plan);
            List<Long> existing = ProcessHandle.of(s.server.pid()).orElseThrow().descendants().map(ProcessHandle::pid).toList();
            s.server.send(executeRequest(701, "performance_suite.execute_plan", selector("cancel", "cancel"), true));
            Path directory = runDirectory(s, "cancel", "cancel");
            awaitFile(directory.resolve("workload.jmeter.jtl"), Duration.ofSeconds(20));
            List<ProcessHandle> children = ProcessHandle.of(s.server.pid()).orElseThrow().descendants()
                    .filter(child -> !existing.contains(child.pid())).toList();
            children.forEach(JavaItScenario::own);
            assertThat(children).as("a real CLI workload must be active before cancellation").isNotEmpty();
            s.server.send(Map.of("jsonrpc", "2.0", "method", "notifications/cancelled", "params", Map.of("requestId", 701)));
            awaitFile(directory.resolve("execution.result.json"), Duration.ofSeconds(45));
            JsonNode persisted = assertRun(s, "cancel", "cancel", "pass");
            assertThat(run(s, "cancel", "cancel")).isEqualTo(persisted.path("directResult"));
            awaitProcessesExit(children);
            assertThat(s.server.hasResponseFor(701)).isFalse();
            s.deactivate();
        }
    }

    private Path stalledLauncher(Path workspace, Path marker) throws Exception {
        return stalledLauncher(workspace, marker, "");
    }

    private Path stalledLauncher(Path workspace, Path marker, String mode) throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path script = workspace.resolve(windows ? "stalled.cmd" : "stalled.sh");
        String command = quote(McpServerProcess.javaBinary(), windows) + " -cp " + quote(testClassesPath().toString(), windows)
                + " " + PerformanceStalledProcess.class.getName() + " " + quote(marker.toString(), windows) + mode;
        Files.writeString(script, windows ? "@echo off\r\n" + command + "\r\n" : "#!/bin/sh\nexec " + command + "\n");
        if (!windows) {
            assertThat(script.toFile().setExecutable(true)).isTrue();
        }
        return script;
    }

    private String quote(String value, boolean windows) {
        if (windows) {
            assertThat(value).doesNotContain("\"", "%", "\r", "\n");
            return "\"" + value + "\"";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private void awaitFile(Path path, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline) && !Files.isRegularFile(path)) {
            Thread.sleep(50);
        }
        assertThat(path).as("real execution must create its evidence before the deadline").isRegularFile();
    }

    private void awaitProcessesExit(List<ProcessHandle> children) throws Exception {
        Instant deadline = Instant.now().plusSeconds(5);
        while (children.stream().anyMatch(ProcessHandle::isAlive) && Instant.now().isBefore(deadline)) {
            Thread.sleep(25);
        }
        assertThat(children).allSatisfy(child -> assertThat(child.isAlive()).isFalse());
    }

    @Test
    void successfulSubprocessExitCannotTurnMissingOrPartialJtlIntoPassingEvidence() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s, "missing", "partial");
            boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
            for (String name : new String[] {"missing", "partial"}) {
                Path script = s.workspace.resolve(name + (windows ? ".cmd" : ".sh"));
                String content = windows ? "@echo off\r\n" : "#!/bin/sh\n";
                if (name.equals("partial")) {
                    content += "echo elapsed,success > workload.jmeter.jtl\n";
                }
                content += windows ? "exit /b 0\r\n" : "exit 0\n";
                Files.writeString(script, content);
                if (!windows) {
                    assertThat(script.toFile().setExecutable(true)).isTrue();
                }
                ObjectNode plan = workload(s);
                contract(plan).withObject("workloadProvider").withObject("options").put("installationPath", script.toString());
                save(s, name, plan);
                assertBlocked(s, name, name.equals("missing") ? "performance_jmeter_results_missing" : "performance_jmeter_results_invalid");
            }
            s.deactivate();
        }
    }

    @ParameterizedTest
    @CsvSource({"stop,stop_on_fail,fail,1", "continue,stop_on_fail,partial_fail,2", "inherit,continue_on_fail,partial_fail,2"})
    void failedPerformancePlansRespectStopContinueAndInheritedPolicy(String onFail, String policy, String status, int count) throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            ObjectNode project = context(s, "first", "second");
            ObjectNode profile = (ObjectNode) project.path("workspaces").get(0).path("executionProfiles").get(0);
            profile.put("executionPolicy", policy);
            ((ObjectNode) profile.path("plans").get(0)).put("onFail", onFail);
            ok(s.server, "project_context.upsert", Map.of("projectName", PROJECT, "payload", project));
            ObjectNode plan = workload(s);
            contract(plan).putObject("workloadProvider").put("type", "builtin");
            save(s, "second", plan);
            contract(plan).withObject("successCriteria").put("minThroughputPerSec", 1.0e100);
            save(s, "first", plan);
            JsonNode result = s.result("execution_orchestration.execute", Map.of("projectName", PROJECT,
                    "executionProfile", PROFILE, "suiteRunId", "policy", "maxPlansPerCall", 2), true);
            assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo(status);
            JsonNode runs = result.path("details").path("planRuns");
            assertThat(runs).hasSize(count);
            assertThat(runs.get(0).path("details").path("runStatus").asText()).isEqualTo("fail");
            JavaItScenario.observe("execution_orchestration.execute", "ASSERTED", onFail + ":" + policy);
            if (count == 2) {
                assertThat(runs.get(1).path("details").path("runStatus").asText()).isEqualTo("pass");
            } else {
                assertThat(s.workspace.resolve(".mcpjvm/" + PROJECT + "/plans/performance/second/runs/policy-2")).doesNotExist();
            }
            s.deactivate();
        }
    }
}
