package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

/** Orders profile script phases in the generated replay. */
final class ExecutionExportScriptPhase {

    private ExecutionExportScriptPhase() {
    }

    static int order(String phase) {
        return switch (phase) {
            case "preRuntime" -> 0;
            case "postRuntime" -> 1;
            case "postHealthcheck" -> 2;
            default -> 3;
        };
    }
}
