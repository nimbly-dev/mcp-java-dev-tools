package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

/** Applies the export filename policy to copied project script names. */
final class ExecutionExportScriptFileName {

    private ExecutionExportScriptFileName() {
    }

    static String safe(String value) {
        String result = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return result.isBlank() ? "script" : result;
    }
}
