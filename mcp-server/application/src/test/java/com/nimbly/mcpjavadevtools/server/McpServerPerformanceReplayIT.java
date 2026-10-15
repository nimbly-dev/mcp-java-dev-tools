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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("orchestration")
@Timeout(240)
class McpServerPerformanceReplayIT extends ReplayMcpSupport {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {404, 200})
    void failedOrEmptyProfilerDownloadBlocksRealReplay(int downloadStatus) throws Exception {
        var proxyHolder = new java.util.concurrent.atomic.AtomicReference<TransportHttpTarget>();
        try (JvmProbeScenario s = JvmProbeScenario.start(); var client = java.net.http.HttpClient.newHttpClient();
                TransportHttpTarget proxy = TransportHttpTarget.start(exchange ->
                        forwardProbe(exchange, s.baseUrl, client, proxyHolder.get(), downloadStatus))) {
            proxyHolder.set(proxy);
            s.attach();
            saveContext(s.server, replayProject(s, "load"));
            ObjectNode plan = PerformanceMcpSupport.workload(s);
            contract(plan).putObject("workloadProvider").put("type", "builtin");
            contract(plan).putObject("analysis").putObject("executionTiming").put("enabled", true)
                    .put("provider", "jfr").put("event", "cpu").put("outputFormat", "jfr");
            contract(plan).withObject("observationTargets").put("probeBaseUrl", proxy.url(""));
            PerformanceMcpSupport.save(s, "load", plan);
            Path directory = generatedReplay(s, "ps1");
            runPackage(directory, replayCommand("ps1", directory), Map.of(), 1);
            Path resultFile = replayResult(directory, "load");
            JsonNode result = JSON.readTree(resultFile.toFile());
            assertThat(result.path("runStatus").asText()).as(result.toPrettyString()).isEqualTo("blocked");
            assertThat(result.path("reasonCode").asText()).isEqualTo("performance_profiler_download_failed");
            assertThat(result.path("failedStep").asText()).isEqualTo("profiler_download");
            assertThat(result.path("executionTiming").path("stop").path("download").path("httpStatus").asInt()).isEqualTo(downloadStatus);
            assertThat(resultFile.getParent().resolve("load.execution-timing.jfr")).doesNotExist();
            assertThat(s.status(s.postKey()).path("hitCount").asLong()).isPositive();
            assertThat(s.success("profiler", Map.of("action", "status")).path("status").asText()).isEqualTo("completed");
            s.deactivate();
        }
    }

    private void forwardProbe(com.sun.net.httpserver.HttpExchange exchange, String base, java.net.http.HttpClient client,
            TransportHttpTarget proxy, int downloadStatus)
            throws java.io.IOException {
        try {
            String body = proxy.received().body();
            if (exchange.getRequestURI().getQuery() != null && exchange.getRequestURI().getQuery().contains("action=download")) {
                TransportHttpTarget.respond(exchange, downloadStatus, "");
                return;
            }
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + exchange.getRequestURI()))
                    .timeout(java.time.Duration.ofSeconds(20)).header("Content-Type", "application/json")
                    .method(exchange.getRequestMethod(), java.net.http.HttpRequest.BodyPublishers.ofString(body));
            var response = client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            TransportHttpTarget.respond(exchange, response.statusCode(), response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("Probe proxy interrupted", interrupted);
        }
    }

    @Test
    void absentReceiverJmeterFailsClosedWithoutInventingSamples() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s.server, replayProject(s, "load"));
            PerformanceMcpSupport.save(s, "load", PerformanceMcpSupport.workload(s));
            Path directory = generatedReplay(s, "ps1");
            String searchPath = java.util.Arrays.stream(System.getenv("PATH").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                    .filter(entry -> !Files.exists(Path.of(entry).resolve("jmeter.bat"))
                            && !Files.exists(Path.of(entry).resolve("jmeter.cmd")) && !Files.exists(Path.of(entry).resolve("jmeter.exe"))
                            && !Files.exists(Path.of(entry).resolve("jmeter")))
                    .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
            runPackage(directory, replayCommand("ps1", directory), Map.of("PATH", searchPath,
                    "MCP_JAVA_DEV_TOOLS_JMETER_HOME", s.workspace.resolve("missing-jmeter").toString()), 1);
            Path result = replayResult(directory, "load");
            JsonNode actual = JSON.readTree(result.toFile());
            assertThat(actual.path("runStatus").asText()).isEqualTo("blocked");
            assertThat(actual.path("reasonCode").asText()).isEqualTo("performance_jmeter_missing");
            assertThat(actual.path("metrics").path("totalRequests").asLong()).isZero();
            assertThat(result.getParent().resolve("load.workload.jmeter.jtl")).doesNotExist();
            s.deactivate();
        }
    }

    @ParameterizedTest
    @CsvSource({"ps1,inherit,1,fail", "sh,continue,2,partial_fail"})
    void replayFailurePolicyAndPrePlanScriptsControlRealOrderedWork(String mode, String onFail, int count, String status) throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            ObjectNode project = replayProject(s, "bad", "after");
            ((ObjectNode) profile(project).path("plans").get(0)).put("onFail", onFail);
            Files.createDirectories(s.workspace.resolve("working"));
            Files.writeString(s.workspace.resolve("setup.js"), "const fs=require('node:fs'),p=require('node:path'),a=process.argv.slice(2);\n"
                    + "const env=a[a.indexOf('--env-file')+1];fs.appendFileSync(p.join(p.dirname(env),'preplan.calls'),'called\\n');\n"
                    + "fs.appendFileSync(env,'SCRIPT_MARKER=produced-by-preplan\\n');\n");
            root(project).putArray("scripts").addObject().put("name", "setup").put("command", "node").put("appdir", "working")
                    .put("envFileArg", "--env-file").put("phase", "prePlan").putArray("args").add("setup.js");
            profile(project).putArray("scriptRefs").addObject().put("name", "setup").put("phase", "prePlan");
            saveContext(s.server, project);
            ObjectNode bad = PerformanceMcpSupport.workload(s);
            contract(bad).putObject("workloadProvider").put("type", "builtin");
            contract(bad).withObject("successCriteria").put("minThroughputPerSec", 1.0e100);
            PerformanceMcpSupport.save(s, "bad", bad);
            ObjectNode after = PerformanceMcpSupport.workload(s);
            contract(after).putObject("workloadProvider").put("type", "builtin");
            PerformanceMcpSupport.save(s, "after", after);
            assertThat(PerformanceMcpSupport.run(s, "bad", "source").path("details").path("runStatus").asText()).isEqualTo("fail");
            PerformanceMcpSupport.entry(bad).withObject("request").withObject("headers").put("X-Script", "${SCRIPT_MARKER}");
            PerformanceMcpSupport.entry(after).withObject("request").withObject("headers").put("X-Script", "${SCRIPT_MARKER}");
            PerformanceMcpSupport.save(s, "bad", bad);
            PerformanceMcpSupport.save(s, "after", after);
            Path directory = generatedReplay(s, mode);
            runPackage(directory, replayCommand(mode, directory), Map.of(), 1);
            Path result = replayResult(directory, "bad");
            assertThat(JSON.readTree(result.toFile()).path("runStatus").asText()).isEqualTo("fail");
            JsonNode summary = JSON.readTree(result.getParent().resolve("execution_orchestration.result.json").toFile());
            assertThat(summary.path("status").asText()).as(summary.toPrettyString()).isEqualTo(status);
            assertThat(summary.path("planRuns")).hasSize(count);
            assertThat(Files.readAllLines(directory.resolve("preplan.calls"))).hasSize(count);
            assertThat(Files.exists(result.getParent().resolve("after.execution.result.json"))).isEqualTo(count == 2);
            if (count == 2) {
                assertThat(JSON.readTree(result.getParent().resolve("after.execution.result.json").toFile())
                        .path("runStatus").asText()).isEqualTo("pass");
            }
            s.deactivate();
        }
    }

    @Test
    void exportedProfilerOwnsAndStopsItsRealJfrSession() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s.server, replayProject(s, "load"));
            ObjectNode plan = PerformanceMcpSupport.workload(s);
            contract(plan).putObject("workloadProvider").put("type", "builtin");
            contract(plan).putObject("analysis").putObject("executionTiming").put("enabled", true).put("provider", "jfr")
                    .put("event", "cpu").put("outputFormat", "jfr");
            PerformanceMcpSupport.save(s, "load", plan);
            assertThat(PerformanceMcpSupport.run(s, "load", "source").path("details").path("runStatus").asText()).isEqualTo("pass");
            Path directory = generatedReplay(s, "ps1");
            runPackage(directory, replayCommand("ps1", directory), Map.of());
            Path result = replayResult(directory, "load");
            assertThat(JSON.readTree(result.toFile()).path("runStatus").asText()).isEqualTo("pass");
            Path jfr = result.getParent().resolve("load.execution-timing.jfr");
            assertThat(Files.size(jfr)).isGreaterThan(68);
            assertThat(java.util.Arrays.copyOf(Files.readAllBytes(jfr), 4)).containsExactly((byte) 0x46, (byte) 0x4c, (byte) 0x52, (byte) 0);
            assertThat(s.success("profiler", Map.of("action", "status")).path("status").asText()).isEqualTo("completed");
            s.deactivate();
        }
    }

    @Test
    void exportedBuiltinTimeoutIsAFailedReplayWithPersistedBlockedEvidence() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start(); TransportHttpTarget slow = TransportHttpTarget.start(exchange -> {
            try {
                Thread.sleep(150);
                TransportHttpTarget.respond(exchange, 200, "{}");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        })) {
            s.attach();
            saveContext(s.server, replayProject(s, "load"));
            ObjectNode plan = PerformanceMcpSupport.workload(s);
            contract(plan).putObject("workloadProvider").put("type", "builtin");
            PerformanceMcpSupport.entry(plan).withObject("transport").put("baseUrl", slow.url("")).remove("healthCheckPath");
            PerformanceMcpSupport.entry(plan).withObject("request").put("path", "/slow").put("timeoutMs", 50);
            PerformanceMcpSupport.save(s, "load", plan);
            assertThat(PerformanceMcpSupport.run(s, "load", "source").path("reasonCode").asText()).isEqualTo("transport_request_timeout");
            Path directory = generatedReplay(s, "ps1");
            runPackage(directory, replayCommand("ps1", directory), Map.of(), 1);
            JsonNode result = JSON.readTree(replayResult(directory, "load").toFile());
            assertThat(result.path("runStatus").asText()).isEqualTo("blocked");
            assertThat(result.path("reasonCode").asText()).isEqualTo("transport_request_timeout");
            s.deactivate();
        }
    }

    private ObjectNode replayProject(JvmProbeScenario s, String... names) throws Exception {
        ObjectNode project = PerformanceMcpSupport.context(s, names);
        root(project).put("envFile", "fixture.env").putObject("variables").putObject("contextBindings").put("Authorization", "REPLAY_AUTH");
        Files.writeString(s.workspace.resolve("fixture.env"), "REPLAY_AUTH=Bearer alice-token\n");
        return project;
    }

    private Path generatedReplay(JvmProbeScenario s, String mode) throws Exception {
        Path directory = export(s.server, s.workspace, Map.of("projectName", PROJECT, "executionProfile", "performance",
                "exportId", "negative", "mode", mode, "includeRuntimeStartup", false, "includeHealthcheckGate", false));
        Files.writeString(directory.resolve("project.env"), "REPLAY_AUTH=\"Bearer alice-token\"\nSCRIPT_MARKER=\n");
        return directory;
    }

    private Path replayResult(Path directory, String name) throws Exception {
        try (var paths = Files.walk(directory.resolve("runs"))) {
            return paths.filter(path -> path.getFileName().toString().equals(name + ".execution.result.json")).findFirst().orElseThrow();
        }
    }

    @Test
    void exportResolvesProfileContextAndTheActiveRegistryBeforeReplay() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            ObjectNode registry = McpServerArtifactConfigIT.registry(s.workspace);
            ObjectNode probes = (ObjectNode) registry.path("profiles").path("local").path("probes");
            probes.remove("two");
            probes.withObject("one").put("baseUrl", s.baseUrl);
            ok(s.server, "probe_config.upsert", Map.of("payload", registry));
            artifact(s.server, "probe_config.reload", Map.of());
            ObjectNode project = PerformanceMcpSupport.context(s, "bound");
            profile(project).withObject("providedContext").put("runLabel", "profile-label");
            root(project).put("envFile", "fixture.env").putObject("variables").putObject("contextBindings")
                    .put("authorization", "authInput");
            Files.writeString(s.workspace.resolve("fixture.env"), "authInput=Bearer alice-token\n");
            saveContext(s.server, project);
            ObjectNode plan = PerformanceMcpSupport.workload(s);
            PerformanceMcpSupport.entry(plan).withObject("transport").put("baseUrl", "${base}");
            PerformanceMcpSupport.entry(plan).withObject("request").withObject("headers")
                    .put("Authorization", "${authorization}").put("X-Context", "${runLabel}");
            contract(plan).withObject("observationTargets").remove("probeBaseUrl");
            contract(plan).withObject("observationTargets").put("probeId", "one");
            PerformanceMcpSupport.save(s, "bound", plan);
            assertThat(PerformanceMcpSupport.run(s, "bound", "source").path("details").path("runStatus").asText()).isEqualTo("pass");
            Path directory = export(s.server, s.workspace, Map.of("projectName", PROJECT, "executionProfile", "performance",
                    "exportId", "bound", "mode", "ps1", "includeRuntimeStartup", false, "includeHealthcheckGate", false));
            Files.writeString(directory.resolve("project.env"), "authInput=\"Bearer alice-token\"\n");
            runPackage(directory, replayCommand("ps1", directory), Map.of("MCP_JAVA_DEV_TOOLS_JMETER_HOME", jmeterHome().toString()));
            s.deactivate();
        }
    }

    @ParameterizedTest
    @CsvSource({"ps1,builtin", "sh,builtin", "ps1,default", "sh,default", "ps1,jmeter", "sh,jmeter"})
    void acceptedPerformancePlansReplayRealWorkloadMetricsAndFreshProbeEvidence(String mode, String provider) throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            ObjectNode project = PerformanceMcpSupport.context(s, "load");
            root(project).put("envFile", "fixture.env").putObject("variables").putObject("contextBindings")
                    .put("Authorization", "REPLAY_AUTH");
            Files.writeString(s.workspace.resolve("fixture.env"), "REPLAY_AUTH=Bearer alice-token\n");
            saveContext(s.server, project);
            ObjectNode plan = PerformanceMcpSupport.workload(s);
            if (provider.equals("builtin")) {
                contract(plan).putObject("workloadProvider").put("type", "builtin");
            } else if (provider.equals("default")) {
                contract(plan).remove("workloadProvider");
            }
            PerformanceMcpSupport.save(s, "load", plan);
            JsonNode source = PerformanceMcpSupport.run(s, "load", "source");
            assertThat(source.path("details").path("runStatus").asText()).as(source.toPrettyString()).isEqualTo("pass");
            Path original = export(s.server, s.workspace, Map.of("projectName", PROJECT, "executionProfile", "performance",
                    "exportId", "workload", "mode", mode, "includeRuntimeStartup", false, "includeHealthcheckGate", false));
            assertThat(packageText(original)).doesNotContain("alice-token", s.workspace.toString(), jmeterHome().toString());
            JsonNode bundle = JSON.readTree(original.resolve("performance-export.bundle.json").toFile());
            assertThat(bundle.path("plans")).hasSize(1);
            assertThat(bundle.path("plans").get(0).path("probeBaseUrl").asText()).isEqualTo(s.baseUrl);
            if (provider.equals("jmeter")) {
                assertThat(original.resolve("artifacts/jmeter/load.workload.jmeter.jmx")).isRegularFile();
            }
            Path relocated = relocate(original, s.workspace.resolve("relocated workload"));
            Files.writeString(relocated.resolve("project.env"), "REPLAY_AUTH=\"Bearer alice-token\"\n");
            runPackage(relocated, replayCommand(mode, relocated), Map.of("MCP_JAVA_DEV_TOOLS_JMETER_HOME", jmeterHome().toString()));
            assertReplay(s, relocated, provider);
            s.deactivate();
            JavaItScenario.observe("execution_profile_export.export", "ASSERTED", provider + "_" + mode + "_real_replay");
        }
    }

    private void assertReplay(JvmProbeScenario s, Path directory, String provider) throws Exception {
        try (var paths = Files.walk(directory.resolve("runs"))) {
            Path result = paths.filter(path -> path.getFileName().toString().equals("load.execution.result.json")).findFirst().orElseThrow();
            JsonNode persisted = JSON.readTree(result.toFile());
            assertThat(persisted.path("status").asText()).as(persisted.toPrettyString()).isEqualTo("executed");
            assertThat(persisted.path("runStatus").asText()).isEqualTo("pass");
            assertThat(persisted.path("metrics").path("totalRequests").asLong()).isGreaterThan(1);
            assertThat(persisted.path("metrics").path("errorRatePct").asDouble()).isZero();
            assertThat(persisted.path("metrics").path("durationMs").asLong()).isGreaterThanOrEqualTo(2000);
            assertThat(persisted.path("requiredLineHits")).hasSize(1);
            assertThat(persisted.path("requiredLineHits").get(0).path("key").asText()).isEqualTo(s.postKey());
            assertThat(persisted.path("requiredLineHits").get(0).path("hit").asBoolean()).isTrue();
            assertThat(s.status(s.postKey()).path("hitCount").asLong()).isPositive();
            JsonNode summary = JSON.readTree(result.getParent().resolve("execution_orchestration.result.json").toFile());
            assertThat(summary.path("status").asText()).isEqualTo("pass");
            if (provider.equals("jmeter")) {
                Path jtl = result.getParent().resolve("load.workload.jmeter.jtl");
                var samples = Files.readAllLines(jtl);
                assertThat(samples.size()).isGreaterThan(2);
                assertThat(samples.getFirst()).contains("elapsed", "success", "responseCode");
                assertThat(samples.subList(1, samples.size())).allSatisfy(sample -> assertThat(sample).contains(",200,", ",true,"));
                assertThat(Files.readString(result.getParent().resolve("load.workload.jmeter.jmx"))).doesNotContain("alice-token");
            }
            assertThat(persisted.toString()).doesNotContain("alice-token");
        }
    }
}
