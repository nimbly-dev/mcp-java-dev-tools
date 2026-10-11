package com.nimbly.mcpjavadevtools.server.core.feature.suite.execution;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Loads a project-owned runtime environment without exposing resolved values. */
public final class SuiteRuntimeEnvironment {

    private static final Pattern ENVIRONMENT_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private SuiteRuntimeEnvironment() {
    }

    /** Returns the process environment overlaid by a workspace-contained dotenv file. */
    public static Map<String, String> load(JsonNode source) {
        Map<String, String> values = new LinkedHashMap<>(System.getenv());
        Path file = environmentFile(source);
        if (file == null) {
            return Map.copyOf(values);
        }
        try {
            for (String line : Files.readAllLines(file)) {
                add(values, line);
            }
        } catch (IOException | SecurityException ignored) {
            // Callers fail closed when a declared binding cannot be resolved.
        }
        return Map.copyOf(values);
    }

    private static Path environmentFile(JsonNode source) {
        String workspaceRoot = source.path("workspaceRoot").asText();
        String envFile = source.path("envFile").asText();
        if (workspaceRoot.isBlank() || envFile.isBlank()) {
            return null;
        }
        try {
            Path workspace = Path.of(workspaceRoot).toAbsolutePath().normalize();
            Path configured = Path.of(envFile);
            Path resolved = (configured.isAbsolute() ? configured : workspace.resolve(configured))
                    .toAbsolutePath().normalize();
            return resolved.startsWith(workspace) ? resolved : null;
        } catch (IllegalArgumentException | SecurityException ignored) {
            return null;
        }
    }

    private static void add(Map<String, String> values, String rawLine) {
        String line = rawLine.trim();
        int separator = line.indexOf('=');
        if (line.isEmpty() || line.startsWith("#") || separator < 1) {
            return;
        }
        String key = line.substring(0, separator).trim();
        String value = line.substring(separator + 1).trim();
        if (ENVIRONMENT_KEY.matcher(key).matches()) {
            values.put(key, unquote(value));
        }
    }

    private static String unquote(String value) {
        if (value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        return first == last && (first == '\'' || first == '"')
                ? value.substring(1, value.length() - 1) : value;
    }
}
