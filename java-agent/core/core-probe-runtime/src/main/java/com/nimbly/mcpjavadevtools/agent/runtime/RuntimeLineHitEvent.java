package com.nimbly.mcpjavadevtools.agent.runtime;

/** Sanitized runtime evidence emitted for a line hit under a bound context. */
public record RuntimeLineHitEvent(
  long sequence,
  long lastSequence,
  long hitCount,
  String correlationExecutionId,
  String correlationSessionId,
    String probeId,
    String lineKey,
    String runtimeInstanceId,
  long timestampEpochMs,
  long firstTimestampEpochMs,
    String keyType,
    String keyFingerprint
) {}
