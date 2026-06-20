package com.nimbly.mcpjavadevtools.agent.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class CaptureEntry {
  final String captureId;
  final String methodKey;

  private final long capturedAtEpoch;
  private final long executionStartedAtEpoch;
  private final long executionEndedAtEpoch;
  private final long executionDurationMs;
  private final Long threadAllocatedBytesDelta;
  private final List<CaptureValue> args;
  private final CaptureValue returnValue;
  private final CaptureValue thrownValue;
  private final List<String> executionPaths;
  private final String redactionMode;

  CaptureEntry(
      String captureId,
      String methodKey,
      long capturedAtEpoch,
      long executionStartedAtEpoch,
      long executionEndedAtEpoch,
      long executionDurationMs,
      Long threadAllocatedBytesDelta,
      List<CaptureValue> args,
      CaptureValue returnValue,
      CaptureValue thrownValue,
      List<String> executionPaths,
      String redactionMode
  ) {
    this.captureId = captureId;
    this.methodKey = methodKey;
    this.capturedAtEpoch = capturedAtEpoch;
    this.executionStartedAtEpoch = executionStartedAtEpoch;
    this.executionEndedAtEpoch = executionEndedAtEpoch;
    this.executionDurationMs = executionDurationMs;
    this.threadAllocatedBytesDelta = threadAllocatedBytesDelta;
    this.args = args;
    this.returnValue = returnValue;
    this.thrownValue = thrownValue;
    this.executionPaths = executionPaths == null ? Collections.emptyList() : executionPaths;
    this.redactionMode = redactionMode;
  }

  CapturePreviewView toPreview(int previewMaxChars) {
    List<CaptureValueView> previewArgs = new ArrayList<>();
    boolean truncatedAny = false;
    for (CaptureValue value : args) {
      CaptureValueView view = value.toView(previewMaxChars);
      previewArgs.add(view);
      truncatedAny = truncatedAny || view.truncated;
    }
    CaptureValueView returnPreview = returnValue == null ? null : returnValue.toView(previewMaxChars);
    CaptureValueView thrownPreview = thrownValue == null ? null : thrownValue.toView(previewMaxChars);
    truncatedAny =
        truncatedAny
            || (returnPreview != null && returnPreview.truncated)
            || (thrownPreview != null && thrownPreview.truncated);
    return CapturePreviewView.available(
        captureId,
        methodKey,
        capturedAtEpoch,
        executionStartedAtEpoch,
        executionEndedAtEpoch,
        executionDurationMs,
        threadAllocatedBytesDelta,
        redactionMode,
        previewArgs,
        returnPreview,
        thrownPreview,
        truncatedAny,
        executionPaths
    );
  }

  CaptureRecordView toRecord() {
    List<CaptureValueView> outArgs = new ArrayList<>();
    boolean truncatedAny = false;
    for (CaptureValue value : args) {
      CaptureValueView view = value.toView(Integer.MAX_VALUE);
      outArgs.add(view);
      truncatedAny = truncatedAny || view.truncated;
    }
    CaptureValueView outReturn = returnValue == null ? null : returnValue.toView(Integer.MAX_VALUE);
    CaptureValueView outThrown = thrownValue == null ? null : thrownValue.toView(Integer.MAX_VALUE);
    truncatedAny =
        truncatedAny || (outReturn != null && outReturn.truncated) || (outThrown != null && outThrown.truncated);
    return new CaptureRecordView(
        captureId,
        methodKey,
        capturedAtEpoch,
        executionStartedAtEpoch,
        executionEndedAtEpoch,
        executionDurationMs,
        threadAllocatedBytesDelta,
        redactionMode,
        outArgs,
        outReturn,
        outThrown,
        truncatedAny,
        executionPaths
    );
  }
}

