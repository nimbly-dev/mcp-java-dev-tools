package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("orchestration")
@Timeout(180)
class McpServerReplayRuntimeIT extends ReplayMcpSupport {
    @ParameterizedTest
    @ValueSource(strings = {"ps1", "sh"})
    void tcpHealthGateConnectsBeforeWorkload(String mode) throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (java.net.ServerSocket tcp = new java.net.ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress());
                TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            tcp.setSoTimeout(10000);
            ObjectNode project = context(workspace, target.url(""), "first");
            ObjectNode external = root(project).putArray("externalSystems").addObject().put("name", "tcp")
                    .put("kind", "http").put("host", "127.0.0.1").put("port", tcp.getLocalPort());
            external.putArray("healthChecks").addObject().put("id", "tcp-ready").put("type", "tcp")
                    .put("target", "127.0.0.1:" + tcp.getLocalPort()).put("required", true);
            saveContext(server, project);
            save(server, "first", generatedPlan(target.url("/after-tcp")));
            Path directory = export(server, workspace, Map.of("projectName", PROJECT, "executionProfile", PROFILE,
                    "exportId", "tcp", "mode", mode, "includeRuntimeStartup", false));
            runPackage(directory, replayCommand(mode, directory), Map.of());
            try (var accepted = tcp.accept()) {
                assertThat(accepted.getInetAddress().isLoopbackAddress()).isTrue();
            }
            assertThat(target.received().uri()).isEqualTo("/after-tcp");
            target.assertNoRequest();
        }
    }

    @ParameterizedTest
    @CsvSource({"ps1,.", "sh,.", "ps1,working dir", "sh,working dir"})
    void portableScriptsRespectRuntimeHealthAndPlanPhases(String mode, String appdir) throws Exception {
        Path workspace = JavaItScenario.workspace();
        Files.createDirectories(workspace.resolve(appdir));
        Files.writeString(workspace.resolve("setup.js"), "const fs=require('node:fs');\n"
                + "const args=process.argv.slice(2);const env=args[args.indexOf('--env-file')+1];\n"
                + "if(!fs.existsSync(env))process.exit(2);\n"
                + "fetch(args[0]).then(r=>{if(r.status!==200)process.exit(3);}).catch(()=>process.exit(4));\n");
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            ObjectNode project = context(workspace, target.url(""), "first", "second");
            runtime(project, target.url("/health"));
            for (String phase : List.of("preRuntime", "postRuntime", "postHealthcheck", "prePlan")) {
                ObjectNode script = root(project).withArray("scripts").addObject().put("name", phase)
                        .put("phase", phase).put("command", "node").put("appdir", appdir).put("envFileArg", "--env-file");
                script.putArray("args").add("setup.js").add(target.url("/phase/" + phase));
                profile(project).withArray("scriptRefs").addObject().put("name", phase).put("phase", phase);
            }
            saveContext(server, project);
            save(server, "first", generatedPlan(target.url("/first")));
            save(server, "second", generatedPlan(target.url("/second")));
            Path original = export(server, workspace, Map.of("projectName", PROJECT, "executionProfile", PROFILE,
                    "exportId", "phases", "mode", mode));
            assertThat(packageText(original)).doesNotContain(workspace.toString());
            Path relocated = relocate(original, workspace.resolve("portable phases"));
            Files.delete(workspace.resolve("setup.js"));
            runPackage(relocated, replayCommand(mode, relocated), Map.of());
            for (String path : List.of("/phase/preRuntime", "/phase/postRuntime", "/health",
                    "/phase/postHealthcheck", "/phase/prePlan", "/first", "/phase/prePlan", "/second")) {
                assertThat(target.received().uri()).isEqualTo(path);
            }
            target.assertNoRequest();
            assertThat(Files.readString(relocated.resolve("replay.log"))).contains("owned-runtime-started");
            String log = Files.readString(relocated.resolve("replay.log"));
            assertThat(log.indexOf("[S] preRuntime")).isLessThan(log.indexOf("[R] owned"));
            assertThat(log.indexOf("[S] postRuntime")).isGreaterThan(log.indexOf("[R] owned"));
        }
    }

    private void runtime(ObjectNode project, String health) {
        profile(project).put("runtimeContextName", "local");
        ObjectNode runtime = root(project).putArray("runtimeContexts").addObject().put("name", "local")
                .put("mode", "terminal").put("autoStart", true);
        runtime.putArray("startups").addObject().put("name", "owned").put("command", "node")
                .putArray("args").add("-e").add("console.log('owned-runtime-started')");
        ObjectNode external = root(project).putArray("externalSystems").addObject().put("name", "fixture")
                .put("kind", "http").put("host", "127.0.0.1").put("port", java.net.URI.create(health).getPort());
        external.putArray("healthChecks").addObject().put("id", "ready").put("type", "http").put("url", health).put("required", true);
    }
}
