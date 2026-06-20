package com.nimbly.mcpjavadevtools.agent.capture;

import java.util.Collections;
import java.util.List;

public final class CapturePreviewView {
  public final boolean available;
  public final String captureId;
  public final String methodKey;
  public final long capturedAtEpoch;
  public final long executionStartedAtEpoch;
  public final long executionEndedAtEpoch;
  public final long executionDurationMs;
  public final Long threadAllocatedBytesDelta;
  public final String redactionMode;
  public final List<CaptureValueView> argsPreview;
  public final CaptureValueView returnPreview;
  public final CaptureValueView thrownPreview;
  public final boolean truncatedAny;
  public final List<String> executionPaths;

  private CapturePreviewView(
      boolean available,
      String captureId,
      String methodKey,
      long capturedAtEpoch,
      long executionStartedAtEpoch,
      long executionEndedAtEpoch,
      long executionDurationMs,
      Long threadAllocatedBytesDelta,
      String redactionMode,
      List<CaptureValueView> argsPreview,
      CaptureValueView returnPreview,
      CaptureValueView thrownPreview,
      boolean truncatedAny,
      List<String> executionPaths
  ) {
    this.available = available;
    this.captureId = captureId;
    this.methodKey = methodKey;
    this.capturedAtEpoch = capturedAtEpoch;
    this.executionStartedAtEpoch = executionStartedAtEpoch;
    this.executionEndedAtEpoch = executionEndedAtEpoch;
    this.executionDurationMs = executionDurationMs;
    this.threadAllocatedBytesDelta = threadAllocatedBytesDelta;
    this.redactionMode = redactionMode;
    this.argsPreview = argsPreview;
    this.returnPreview = returnPreview;
    this.thrownPreview = thrownPreview;
    this.truncatedAny = truncatedAny;
    this.executionPaths = executionPaths;
  }

  static CapturePreviewView unavailable(String redactionMode) {
    return new CapturePreviewView(
        false,
        null,
        null,
        0L,
        0L,
        0L,
        0L,
        null,
        redactionMode,
        Collections.emptyList(),
        null,
        null,
        false,
        Collections.emptyList()
    );
  }

  static CapturePreviewView available(
      String captureId,
      String methodKey,
      long capturedAtEpoch,
      long executionStartedAtEpoch,
      long executionEndedAtEpoch,
      long executionDurationMs,
      Long threadAllocatedBytesDelta,
      String redactionMode,
      List<CaptureValueView> argsPreview,
      CaptureValueView returnPreview,
      CaptureValueView thrownPreview,
      boolean truncatedAny,
      List<String> executionPaths
  ) {
    return new CapturePreviewView(
        true,
        captureId,
        methodKey,
        capturedAtEpoch,
        executionStartedAtEpoch,
        executionEndedAtEpoch,
        executionDurationMs,
        threadAllocatedBytesDelta,
        redactionMode,
        argsPreview,
        returnPreview,
        thrownPreview,
        truncatedAny,
        executionPaths
    );
  }
}

