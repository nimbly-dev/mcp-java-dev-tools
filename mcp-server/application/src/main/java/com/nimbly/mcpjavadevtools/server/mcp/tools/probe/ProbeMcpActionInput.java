package com.nimbly.mcpjavadevtools.server.mcp.tools.probe;

import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionHttp;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * MCP transport fields mapped into action-specific Core requests by the Application Adapter.
 *
 * @param baseUrl optional direct Probe URL
 * @param probeId optional registered Probe identity
 * @param http optional protected endpoint headers for check
 * @param timeoutMs optional endpoint timeout
 * @param key optional Strict Line Key or method key
 * @param keys optional Strict Line Key batch
 * @param lineHint optional line hint for a method key
 * @param className optional fully qualified class reset selector
 * @param pollIntervalMs optional wait poll interval
 * @param maxRetries optional wait retry count
 * @param captureId required capture identity for capture
 * @param action arm/disarm for actuate or lifecycle command for profiler
 * @param sessionId optional session identity
 * @param actuatorId optional actuation audit identity
 * @param targetKey optional actuation Strict Line Key
 * @param returnBoolean optional actuation branch decision
 * @param ttlMs optional actuation TTL
 * @param provider optional profiler provider intent
 * @param event optional profiler event
 * @param intervalNanos optional profiler sampling interval
 * @param outputPath optional profiler output path
 * @param outputFormat optional profiler format
 */
public record ProbeMcpActionInput(
        @Nullable String baseUrl,
        @Nullable String probeId,
        @Nullable McpActionHttp http,
        @Nullable Integer timeoutMs,
        @Nullable String key,
        @Nullable List<String> keys,
        @Nullable Integer lineHint,
        @Nullable String className,
        @Nullable Integer pollIntervalMs,
        @Nullable Integer maxRetries,
        @Nullable String captureId,
        @Nullable String action,
        @Nullable String sessionId,
        @Nullable String actuatorId,
        @Nullable String targetKey,
        @Nullable Boolean returnBoolean,
        @Nullable Long ttlMs,
        @Nullable String provider,
        @Nullable String event,
        @Nullable Long intervalNanos,
        @Nullable String outputPath,
        @Nullable String outputFormat) {
}
