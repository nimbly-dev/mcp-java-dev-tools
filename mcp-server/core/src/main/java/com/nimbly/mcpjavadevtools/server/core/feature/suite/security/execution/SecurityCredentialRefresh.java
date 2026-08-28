package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Executes only declared bounded prePlan credential-refresh scripts without exposing their output. */
final class SecurityCredentialRefresh {

    private static final long TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(20);
    private static final long TERMINATION_GRACE_MILLIS = TimeUnit.SECONDS.toMillis(2);
    private final long timeoutMillis;

    SecurityCredentialRefresh() {
        this(TIMEOUT_MILLIS);
    }

    SecurityCredentialRefresh(long timeoutMillis) {
        if (timeoutMillis < 1 || timeoutMillis > TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("credential refresh timeout is outside the supported bounds");
        }
        this.timeoutMillis = timeoutMillis;
    }

    Result refresh(JsonNode source, Map<String, String> environment) {
        for (JsonNode reference : source.path("profileScriptRefs")) {
            JsonNode script = script(source.path("scripts"), reference.path("name").asText());
            if (script != null && "prePlan".equals(phase(reference, script)) && !run(source, script, environment)) {
                return new Result(false, "security_credential_refresh_failed");
            }
        }
        return new Result(true, null);
    }

    private static JsonNode script(JsonNode scripts, String name) {
        for (JsonNode candidate : scripts) {
            if (name.equals(candidate.path("name").asText())) {
                return candidate;
            }
        }
        return null;
    }

    private static String phase(JsonNode reference, JsonNode script) {
        return reference.path("phase").asText(script.path("phase").asText("prePlan"));
    }

    private boolean run(JsonNode source, JsonNode script, Map<String, String> environment) {
        String command = command(script.path("command").asText());
        Path workspace = Path.of(source.path("workspaceRoot").asText()).toAbsolutePath().normalize();
        Path directory = directory(workspace, script.path("appdir").asText());
        if (command == null || directory == null) {
            return false;
        }
        List<String> arguments = arguments(command, script, workspace, source);
        if (arguments.isEmpty()) {
            return false;
        }
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(arguments).directory(directory.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().putAll(environment);
            script.path("env").fields().forEachRemaining(entry -> builder.environment().put(entry.getKey(), entry.getValue().asText()));
            process = builder.start();
            process.getOutputStream().close();
            return completed(process);
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            if (process != null) {
                destroyAfterInterrupt(process);
            }
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean completed(Process process) throws InterruptedException {
        if (process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            return process.exitValue() == 0;
        }
        terminate(process);
        return false;
    }

    private static void terminate(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private static void destroyAfterInterrupt(Process process) {
        process.destroy();
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static String command(String value) {
        return switch (value) {
            case "node", "python", "sh" -> value;
            case "ps" -> "powershell";
            default -> null;
        };
    }

    private static Path directory(Path workspace, String value) {
        Path candidate = value == null || value.isBlank() ? workspace : workspace.resolve(value).normalize();
        return candidate.startsWith(workspace) ? candidate : null;
    }

    private static List<String> arguments(String command, JsonNode script, Path workspace, JsonNode source) {
        List<String> values = new ArrayList<>();
        values.add(command);
        for (JsonNode argument : script.path("args")) {
            String resolved = resolve(workspace, argument.asText());
            if (resolved == null) {
                return List.of();
            }
            values.add(resolved);
        }
        String envFileArgument = script.path("envFileArg").asText();
        String envFile = source.path("envFile").asText();
        if (!envFileArgument.isBlank() && !envFile.isBlank() && !values.contains(envFileArgument)) {
            values.add(envFileArgument);
            String resolved = resolve(workspace, envFile);
            if (resolved == null) {
                return List.of();
            }
            values.add(resolved);
        }
        return List.copyOf(values);
    }

    private static String resolve(Path workspace, String value) {
        if (!value.contains("/") && !value.contains("\\")) {
            return value;
        }
        Path candidate = workspace.resolve(value).normalize();
        return candidate.startsWith(workspace) ? candidate.toString() : null;
    }

    record Result(boolean successful, String reasonCode) {
    }
}
