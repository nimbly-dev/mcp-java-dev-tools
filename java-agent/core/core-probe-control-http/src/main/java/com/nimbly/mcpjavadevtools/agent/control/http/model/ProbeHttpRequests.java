package com.nimbly.mcpjavadevtools.agent.control.http.model;

import java.util.List;

public final class ProbeHttpRequests {
  private ProbeHttpRequests() {}

  public record StatusBatchRequest(List<String> keys) {}

  public record ResetRequest(String key, List<String> keys, String className) {}

  public record ActuateRequest(
      String action,
      String sessionId,
      String actuatorId,
      String targetKey,
      Boolean returnBoolean,
      Long ttlMs
  ) {}

  public record ProfilerRequest(
      String action,
      String sessionId,
      String event,
      Long intervalNanos,
      String outputPath,
      String outputFormat
  ) {}

  public record CorrelationConfigRequest(
      String sessionId, String executionId, String eventKeyPath, Long leaseTtlMs, Boolean release
  ) {}
}
