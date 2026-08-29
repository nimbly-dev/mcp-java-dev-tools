package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import java.util.List;
import java.util.Map;

/** One exported project script invocation. */
record ExecutionExportScriptInvocation(
        String name,
        String phase,
        String command,
        List<String> args,
        String appdir,
        Map<String, String> env) {
}
