package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("orchestration")
@Tag("orchestration-docker")
@Timeout(240)
class McpServerDockerReplayIT extends ReplayMcpSupport {
    @Test
    void nativeLinuxShellReplaysInsideTheOwnedFixtureContainer() throws Exception {
        Path workspace = JavaItScenario.workspace();
        String owner = "java-it-655-" + UUID.randomUUID();
        String health = "http://127.0.0.1:8080/actuator/health";
        try (McpServerProcess server = start(workspace)) {
            ObjectNode project = context(workspace, health, "health");
            ObjectNode system = root(project).putArray("externalSystems").addObject().put("name", "fixture")
                    .put("kind", "http").put("host", "127.0.0.1").put("port", 8080);
            system.putArray("healthChecks").addObject().put("id", "up").put("type", "http").put("url", health).put("required", true);
            saveContext(server, project);
            save(server, "health", generatedPlan(health));
            Path directory = export(server, workspace, Map.of("projectName", PROJECT, "executionProfile", PROFILE,
                    "exportId", "native", "mode", "sh", "includeRuntimeStartup", false));
            Files.copy(repositoryRoot().resolve("test/fixtures/spring-apps/social-platform/post-service/post-app/target/"
                    + "post-app-0.1.0-SNAPSHOT.jar"), directory.resolve("fixture.jar"));
            Files.writeString(directory.resolve("compose.yaml"), compose(owner, freePort())
                    .replace("volumes: [", "volumes: ['.:/replay:ro', "));
            String docker = executable("mcpItDocker", "docker");
            try {
                runPackage(directory, List.of(docker, "compose", "-f", "compose.yaml", "up", "-d"), Map.of());
                runPackage(directory, List.of(docker, "compose", "-f", "compose.yaml", "exec", "-T", "-w", "/replay",
                        "fixture", "bash", "run-execution-profile.sh"), Map.of());
                assertThat(Files.readString(directory.resolve("replay.log"))).contains("{\"status\":\"UP\"}", "replayed");
            } finally {
                Files.copy(directory.resolve("replay.log"), directory.resolve("native-replay.log"));
                runPackage(directory, List.of(docker, "compose", "-f", "compose.yaml", "down", "--remove-orphans"), Map.of());
                runPackage(directory, List.of(docker, "ps", "-a", "--filter", "label=com.docker.compose.project=" + owner,
                        "--format", "{{.ID}}"), Map.of());
                assertThat(Files.readString(directory.resolve("replay.log")).trim()).isEmpty();
            }
            JavaItScenario.observe("execution_profile_export.export", "ASSERTED", "native_linux_shell_and_cleanup");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ps1", "sh"})
    void dockerStartupHealthAndWorkloadReplayUseOwnedPortablePrerequisites(String mode) throws Exception {
        Path workspace = JavaItScenario.workspace();
        String owner = "java-it-655-" + UUID.randomUUID().toString();
        int port = freePort();
        String health = "http://127.0.0.1:" + port + "/actuator/health";
        try (McpServerProcess server = start(workspace)) {
            ObjectNode project = context(workspace, health, "health");
            ObjectNode runtime = root(project).putArray("runtimeContexts").addObject().put("name", "docker")
                    .put("mode", "docker").put("autoStart", true).put("composeFile", "compose.yaml");
            runtime.putArray("startups");
            profile(project).put("runtimeContextName", "docker");
            ObjectNode system = root(project).putArray("externalSystems").addObject().put("name", "fixture")
                    .put("kind", "http").put("host", "127.0.0.1").put("port", port);
            system.putArray("healthChecks").addObject().put("id", "up").put("type", "http").put("url", health).put("required", true);
            saveContext(server, project);
            save(server, "health", generatedPlan(health));
            Path original = export(server, workspace, Map.of("projectName", PROJECT, "executionProfile", PROFILE,
                    "exportId", "docker", "mode", mode));
            Path relocated = relocate(original, workspace.resolve("docker package"));
            // The receiver provisions declared runtime prerequisites; test inputs never manufacture run results.
            Files.copy(repositoryRoot().resolve("test/fixtures/spring-apps/social-platform/post-service/post-app/target/"
                    + "post-app-0.1.0-SNAPSHOT.jar"), relocated.resolve("fixture.jar"));
            Files.writeString(relocated.resolve("compose.yaml"), compose(owner, port));
            String docker = executable("mcpItDocker", "docker");
            try {
                runPackage(relocated, List.of(docker, "version", "--format", "{{.Server.Version}}"), Map.of());
                runPackage(relocated, replayCommand(mode, relocated), Map.of());
                assertReplayOutput(relocated, mode);
                Files.move(relocated.resolve("replay.log"), relocated.resolve("docker-replay.log"));
                runPackage(relocated, List.of(docker, "ps", "--filter", "label=com.docker.compose.project=" + owner,
                        "--format", "{{.ID}}"), Map.of());
                assertThat(Files.readString(relocated.resolve("replay.log")).trim()).matches("[a-f0-9]{12}");
            } finally {
                if (Files.exists(relocated.resolve("replay.log")) && !Files.exists(relocated.resolve("docker-replay.log"))) {
                    Files.copy(relocated.resolve("replay.log"), relocated.resolve("docker-replay.log"));
                }
                runPackage(relocated, List.of(docker, "compose", "-f", "compose.yaml", "down", "--remove-orphans"), Map.of());
                runPackage(relocated, List.of(docker, "ps", "-a", "--filter", "label=com.docker.compose.project=" + owner,
                        "--format", "{{.ID}}"), Map.of());
                assertThat(Files.readString(relocated.resolve("replay.log")).trim()).isEmpty();
            }
            JavaItScenario.observe("execution_profile_export.export", "ASSERTED", "docker_" + mode + "_replay_and_cleanup");
        }
    }

    private void assertReplayOutput(Path directory, String mode) throws Exception {
        String replay = Files.readString(directory.resolve("replay.log"));
        assertThat(replay).contains("replay workload");
        if (mode.equals("ps1")) {
            assertThat(replay).contains("status=", "200");
        } else {
            assertThat(replay).contains("{\"status\":\"UP\"}", "replayed");
        }
    }

    private String compose(String owner, int port) {
        return "name: " + owner + "\nservices:\n  fixture:\n    image: eclipse-temurin:21-jre\n"
                + "    command: [java, -jar, /fixture.jar, '--server.port=8080', '--spring.main.banner-mode=off']\n"
                + "    volumes: ['./fixture.jar:/fixture.jar:ro']\n    ports: ['127.0.0.1:" + port + ":8080']\n";
    }
}
