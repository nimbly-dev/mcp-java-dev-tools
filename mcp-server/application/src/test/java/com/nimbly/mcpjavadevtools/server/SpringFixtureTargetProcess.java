package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Executable Spring Boot fixture used by packaged MCP acceptance tests. */
final class SpringFixtureTargetProcess implements AutoCloseable {

    private final Process process;
    private final int port;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1)).build();

    private SpringFixtureTargetProcess(Process process, int port) {
        this.process = process;
        this.port = port;
    }

    static SpringFixtureTargetProcess start(Path fixtureJar) throws IOException {
        return start(fixtureJar, List.of(), List.of());
    }

    static SpringFixtureTargetProcess start(
            Path fixtureJar,
            List<String> javaArguments,
            List<String> applicationArguments) throws IOException {
        int port = McpServerStdioSupport.freePort();
        List<String> command = new ArrayList<>();
        command.add(McpServerProcess.javaBinary());
        command.addAll(javaArguments);
        command.add("-jar");
        command.add(fixtureJar.toString());
        command.addAll(applicationArguments);
        command.add("--server.port=" + port);
        Process process = new ProcessBuilder(command)
                .directory(JavaItScenario.workspace().toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        JavaItScenario.own(process);
        return new SpringFixtureTargetProcess(process, port);
    }

    long pid() {
        return process.pid();
    }

    long processStartEpochMs() {
        return process.toHandle().info().startInstant().orElseThrow().toEpochMilli();
    }

    int port() {
        return port;
    }

    boolean isAlive() {
        return process.isAlive();
    }

    boolean isHealthy() throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                        .timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200
                && McpServerStdioSupport.JSON.readTree(response.body()).path("status").asText()
                        .equalsIgnoreCase("up");
    }

    void awaitHealthy() throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                fail("Spring social-platform fixture exited before readiness.");
            }
            try {
                if (isHealthy()) {
                    return;
                }
            } catch (IOException ignored) {
                // The Spring fixture is still starting its embedded server.
            }
            Thread.sleep(250);
        }
        fail("Spring social-platform fixture did not become healthy within 30 seconds.");
    }

    boolean awaitSidecarReachability(int sidecarPort, boolean expected, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (sidecarReachable(sidecarPort) == expected) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    private boolean sidecarReachable(int sidecarPort) throws InterruptedException {
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + sidecarPort
                                    + "/__probe/status?key=mcpjvm633.acceptance.HealthCheck%23status%3A1"))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (IOException ignored) {
            return false;
        }
    }

    @Override
    public void close() throws Exception {
        JavaItScenario.stopTree(process.toHandle());
    }
}
