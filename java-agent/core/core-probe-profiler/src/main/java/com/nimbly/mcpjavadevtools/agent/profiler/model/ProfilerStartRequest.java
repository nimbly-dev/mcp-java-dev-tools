package com.nimbly.mcpjavadevtools.agent.profiler.model;

public record ProfilerStartRequest(
    String sessionId,
    String event,
    Long intervalNanos,
    String outputPath,
    String outputFormat
) {}
