package com.nimbly.mcpjavadevtools.agent.profiler.model;

public record ProfilerStopResult(
    String sessionId,
    String provider,
    String status,
    boolean supported,
    Long stoppedAtEpochMs,
    String outputPath,
    String outputFormat,
    String detail
) {}
