package com.nimbly.mcpjavadevtools.agent.runtime;

/** Normalized event identity supplied by a supported consumer adapter. */
public record CorrelationEventEnvelope(
    String correlationExecutionId,
    String correlationSessionId,
    String keyType,
    String keyValue
) {}
