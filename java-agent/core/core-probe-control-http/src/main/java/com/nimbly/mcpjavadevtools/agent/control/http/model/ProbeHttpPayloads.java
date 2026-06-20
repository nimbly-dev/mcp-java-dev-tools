package com.nimbly.mcpjavadevtools.agent.control.http.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public final class ProbeHttpPayloads {
  private ProbeHttpPayloads() {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ErrorEnvelope(
      String error,
      String scope
  ) {}

  public record CaptureNotFoundEnvelope(
      String contractVersion,
      String error,
      String captureId
  ) {}

  public record StatusEnvelope(
      String contractVersion,
      ProbePayload probe,
      CapturePreviewPayload capturePreview,
      RuntimePayload runtime
  ) {}

  public record StatusBatchRow(
      boolean ok,
      ProbePayload probe,
      CapturePreviewPayload capturePreview,
      RuntimePayload runtime
  ) {}

  public record StatusBatchEnvelope(
      String contractVersion,
      boolean ok,
      int count,
      List<StatusBatchRow> results
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ResetEnvelope(
      String contractVersion,
      boolean ok,
      String key,
      Boolean lineResolvable,
      String lineValidation
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ResetRow(
      boolean ok,
      String key,
      Boolean lineResolvable,
      String lineValidation
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ResetBatchEnvelope(
      String contractVersion,
      boolean ok,
      String selector,
      String className,
      int count,
      List<ResetRow> results,
      String reason
  ) {}

  public record ActuateEnvelope(
      String contractVersion,
      boolean ok,
      String action,
      String mode,
      String sessionId,
      String actuatorId,
      String targetKey,
      Boolean returnBoolean,
      Long ttlMs,
      Long expiresAtEpoch,
      String scopeState
  ) {}

  public record CaptureEnvelope(
      String contractVersion,
      CaptureRecordPayload capture
  ) {}

  public record ProfilerEnvelope(
      String contractVersion,
      boolean ok,
      String action,
      ProfilerPayload profiler
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ProbePayload(
      String key,
      long hitCount,
      long lastHitEpoch,
      Boolean lineResolvable,
      String lineValidation
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CapturePreviewPayload(
      boolean available,
      String redactionMode,
      String captureId,
      String methodKey,
      Long capturedAtEpoch,
      Long executionStartedAtEpoch,
      Long executionEndedAtEpoch,
      Long executionDurationMs,
      Long threadAllocatedBytesDelta,
      List<CapturePreviewArgPayload> argsPreview,
      CapturePreviewValuePayload returnPreview,
      CapturePreviewValuePayload thrownPreview,
      Boolean truncatedAny,
      List<String> executionPaths
  ) {}

  public record CapturePreviewArgPayload(
      int index,
      boolean truncated,
      int originalLength,
      boolean redacted
  ) {}

  public record CapturePreviewValuePayload(
      boolean truncated,
      int originalLength,
      boolean redacted
  ) {}

  public record RuntimePayload(
      String mode,
      String sessionId,
      String actuatorId,
      String actuateTargetKey,
      Boolean actuateReturnBoolean,
      Long expiresAtEpoch,
      String scopeState,
      int activeSessionCount,
      long serverEpoch,
      RuntimeStringSignalPayload applicationType,
      RuntimePortSignalPayload appPort
  ) {}

  public record RuntimeStringSignalPayload(
      String value,
      String source,
      double confidence
  ) {}

  public record RuntimePortSignalPayload(
      Integer value,
      String source,
      double confidence
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ProfilerPayload(
      String status,
      String provider,
      boolean supported,
      String sessionId,
      Long startedAtEpochMs,
      Long stoppedAtEpochMs,
      String event,
      Long intervalNanos,
      String outputPath,
      String outputFormat,
      String detail
  ) {}

  public record CaptureRecordPayload(
      String captureId,
      String methodKey,
      long capturedAtEpoch,
      long executionStartedAtEpoch,
      long executionEndedAtEpoch,
      long executionDurationMs,
      Long threadAllocatedBytesDelta,
      String redactionMode,
      List<CaptureArgPayload> args,
      CaptureValuePayload returnValue,
      CaptureValuePayload thrownValue,
      boolean truncatedAny,
      List<String> executionPaths
  ) {}

  public record CaptureArgPayload(
      int index,
      String value,
      boolean truncated,
      int originalLength,
      boolean redacted
  ) {}

  public record CaptureValuePayload(
      String value,
      boolean truncated,
      int originalLength,
      boolean redacted
  ) {}
}
