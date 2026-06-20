package com.nimbly.mcpjavadevtools.agent.capture;

import java.util.List;

public final class CaptureRecordView {
  public final String captureId;
  public final String methodKey;
  public final long capturedAtEpoch;
  public final long executionStartedAtEpoch;
  public final long executionEndedAtEpoch;
  public final long executionDurationMs;
  public final Long threadAllocatedBytesDelta;
  public final String redactionMode;
  public final List<CaptureValueView> args;
  public final CaptureValueView returnValue;
  public final CaptureValueView thrownValue;
  public final boolean truncatedAny;
  public final List<String> executionPaths;

  CaptureRecordView(
      String captureId,
      String methodKey,
      long capturedAtEpoch,
      long executionStartedAtEpoch,
      long executionEndedAtEpoch,
      long executionDurationMs,
      Long threadAllocatedBytesDelta,
      String redactionMode,
      List<CaptureValueView> args,
      CaptureValueView returnValue,
      CaptureValueView thrownValue,
      boolean truncatedAny,
      List<String> executionPaths
  ) {
    this.captureId = captureId;
    this.methodKey = methodKey;
    this.capturedAtEpoch = capturedAtEpoch;
    this.executionStartedAtEpoch = executionStartedAtEpoch;
    this.executionEndedAtEpoch = executionEndedAtEpoch;
    this.executionDurationMs = executionDurationMs;
    this.threadAllocatedBytesDelta = threadAllocatedBytesDelta;
    this.redactionMode = redactionMode;
    this.args = args;
    this.returnValue = returnValue;
    this.thrownValue = thrownValue;
    this.truncatedAny = truncatedAny;
    this.executionPaths = executionPaths;
  }
}

