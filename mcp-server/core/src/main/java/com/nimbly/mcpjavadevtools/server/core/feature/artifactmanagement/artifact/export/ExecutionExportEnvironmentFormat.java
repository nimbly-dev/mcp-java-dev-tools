package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import java.util.Locale;
import java.util.Map;

/** Owns dotenv parsing, rendering, and export credential-key policy. */
final class ExecutionExportEnvironmentFormat {

    private ExecutionExportEnvironmentFormat() {
    }

    static void parseDotEnv(String text, Map<String, String> values) {
        for (String raw : text.replace("\r", "").split("\n")) {
            String line = raw.trim();
            int separator = line.indexOf('=');
            if (line.isBlank() || line.startsWith("#") || separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            if (key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                String value = line.substring(separator + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        }
    }

    static String dotenvValue(String value) {
        if (value == null || value.matches("[A-Za-z0-9_./:@-]*")) {
            return value == null ? "" : value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static boolean isSensitiveEnvKey(String key) {
        return key.matches("(?i).*(AUTH|BEARER|TOKEN|SECRET|PASSWORD|CREDENTIAL|USERNAME).*");
    }

    static String environmentKey(String value) {
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }
}
