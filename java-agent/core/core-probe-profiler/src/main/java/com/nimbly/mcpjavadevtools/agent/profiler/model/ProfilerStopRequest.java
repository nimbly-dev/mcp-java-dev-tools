package com.nimbly.mcpjavadevtools.agent.profiler.model;

public record ProfilerStopRequest(
    String sessionId,
    String outputPath,
    String outputFormat
) {}
