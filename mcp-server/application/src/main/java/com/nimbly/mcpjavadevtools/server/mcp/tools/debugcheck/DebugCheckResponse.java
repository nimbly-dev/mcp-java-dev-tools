package com.nimbly.mcpjavadevtools.server.mcp.tools.debugcheck;

public record DebugCheckResponse(
        boolean ok,
        String serverTime,
        String version,
        String buildFingerprint,
        long pid,
        Long ppid,
        String workspaceRoot) {
}
