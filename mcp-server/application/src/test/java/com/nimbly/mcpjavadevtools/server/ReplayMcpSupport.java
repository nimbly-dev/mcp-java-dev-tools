package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bounded execution of the product-generated packages with owned-process cleanup. */
abstract class ReplayMcpSupport extends OrchestrationMcpSupport {
    static Path export(McpServerProcess server, Path workspace, Map<String, Object> input) throws Exception {
        JsonNode result = execute(server, "execution_profile_export.export", input, true).path("result");
        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("ok");
        Path directory = workspace.resolve(".mcpjvm/" + PROJECT + "/exports/" + input.get("exportId"));
        JsonNode manifest = JSON.readTree(directory.resolve("manifest.json").toFile());
        assertThat(manifest.path("exportId").asText()).isEqualTo(input.get("exportId"));
        assertThat(manifest.path("mode").asText()).isEqualTo(input.getOrDefault("mode", input.get("type")));
        assertThat(manifest.path("workload")).isNotEmpty();
        return directory;
    }

    static String executable(String property, String fallback) {
        return System.getProperty(property, fallback);
    }

    static List<String> replayCommand(String mode, Path directory) {
        if (mode.equals("ps1")) {
            return List.of(executable("mcpItPowerShell", "pwsh"), "-NoProfile", "-NonInteractive", "-File",
                    directory.resolve("run-execution-profile.ps1").toString());
        }
        if (mode.equals("sh")) {
            return List.of(executable("mcpItBash", "bash"), "run-execution-profile.sh");
        }
        String newman = System.getProperty("mcpItNewmanScript", "");
        assertThat(newman).as("Postman replay requires -DmcpItNewmanScript=<newman/bin/newman.js>").isNotBlank();
        assertThat(Path.of(newman)).isRegularFile();
        return List.of(executable("mcpItNode", "node"), newman, "run", "replay.postman.json", "-e", "environment.postman.json");
    }

    static void runPackage(Path directory, List<String> command, Map<String, String> environment) throws Exception {
        runPackage(directory, command, environment, 0);
    }

    static void runPackage(Path directory, List<String> command, Map<String, String> environment, int expectedExit) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.redirectErrorStream(true).redirectOutput(directory.resolve("replay.log").toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        JavaItScenario.own(process);
        process.getOutputStream().close();
        Map<Long, ProcessHandle> owned = new java.util.LinkedHashMap<>();
        owned.put(process.pid(), process.toHandle());
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (process.isAlive() && System.nanoTime() < deadline) {
                process.descendants().forEach(child -> {
                    if (owned.putIfAbsent(child.pid(), child) == null) {
                        JavaItScenario.own(child);
                    }
                });
                process.waitFor(50, TimeUnit.MILLISECONDS);
            }
            assertThat(process.isAlive()).as("replay must finish within its deadline").isFalse();
            assertThat(process.exitValue()).as("generated replay exit status; inspect retained replay.log on failure").isEqualTo(expectedExit);
        } finally {
            JavaItScenario.stopOwnedProcesses(List.copyOf(owned.values()));
        }
    }

    static Path relocate(Path source, Path target) throws Exception {
        // Windows scanners can transiently hold a newly copied JAR; the move must still succeed.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                return Files.move(source, target);
            } catch (java.nio.file.AccessDeniedException denied) {
                if (System.nanoTime() >= deadline) {
                    throw denied;
                }
                Thread.sleep(100);
            }
        }
    }

    static String packageText(Path directory) throws Exception {
        StringBuilder content = new StringBuilder();
        try (var files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                content.append(Files.readString(file));
            }
        }
        return content.toString();
    }
}
