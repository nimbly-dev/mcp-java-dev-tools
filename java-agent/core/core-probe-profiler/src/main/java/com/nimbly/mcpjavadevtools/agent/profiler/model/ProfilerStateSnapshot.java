package com.nimbly.mcpjavadevtools.agent.profiler.model;

public record ProfilerStateSnapshot(
    String status,
    String provider,
    boolean supported,
    String sessionId,
    Long startedAtEpochMs,
    String event,
    Long intervalNanos,
    String outputPath,
    String detail
) {}
