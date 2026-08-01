package com.nimbly.mcpjavadevtools.agent.debug.api;

import com.nimbly.mcpjavadevtools.agent.capture.CapturePreviewView;
import com.nimbly.mcpjavadevtools.agent.capture.CaptureRecordView;
import com.nimbly.mcpjavadevtools.agent.capture.CaptureValueView;
import com.nimbly.mcpjavadevtools.agent.capture.ProbeCaptureStore;
import com.nimbly.mcpjavadevtools.agent.failure.FailureTraceAnalyzer;
import java.util.List;

/** Stable capture and failure-analysis capability surface for HTTP and bootstrap consumers. */
public final class DebugApi {
  private DebugApi() {}

  public static void configureCapture(boolean enabled, int maxKeys, int maxArgs, int bufferSize,
                                      int previewMaxChars, int storedMaxChars, String redactionMode) {
    ProbeCaptureStore.configureCapture(
        enabled, maxKeys, maxArgs, bufferSize, previewMaxChars, storedMaxChars, redactionMode);
  }

  public static void configureExecutionPathScope(List<String> includePatterns, List<String> excludePatterns) {
    ProbeCaptureStore.configureExecutionPathScope(
        includePatterns, excludePatterns);
  }

  public static String captureRedactionMode() {
    return ProbeCaptureStore.getCaptureRedactionMode();
  }

  public static CapturePreview capturePreviewForKey(String key) {
    return toApi(ProbeCaptureStore.getCapturePreviewForKey(key));
  }

  public static CaptureRecord captureById(String captureId) {
    return toApi(ProbeCaptureStore.getCaptureById(captureId));
  }

  public static DebugApi.FailureFingerprint failureFingerprintByCaptureId(String captureId) {
    return toApi(ProbeCaptureStore.getFailureFingerprintByCaptureId(captureId));
  }

  public static void resetByKey(String key) {
    ProbeCaptureStore.resetByKey(key);
  }

  public static long currentThreadAllocatedBytes() {
    return ProbeCaptureStore.currentThreadAllocatedBytes();
  }

  public static void captureByClassMethod(String className, String methodName, Object[] arguments,
                                          Object returnValue, Throwable thrown, long startedAtEpoch,
                                          long endedAtEpoch, long allocatedBytesAtEnter) {
    ProbeCaptureStore.captureByClassMethod(
        className, methodName, arguments, returnValue, thrown, startedAtEpoch, endedAtEpoch,
        allocatedBytesAtEnter);
  }

  public static DebugApi.FailureTraceAnalysis analyze(String trace) {
    return toApi(FailureTraceAnalyzer.analyze(trace));
  }

  public static DebugApi.FailureComparison compare(String expectedExceptionType, String expectedRootCauseType,
                                                    String expectedMethodKey, DebugApi.FailureFingerprint observed) {
    var internal = toInternal(observed);
    return toApi(com.nimbly.mcpjavadevtools.agent.failure.FailureComparison.compare(
        expectedExceptionType, expectedRootCauseType, expectedMethodKey, internal));
  }

  private static CapturePreview toApi(CapturePreviewView value) {
    if (value == null) return null;
    return new CapturePreview(value.available, value.captureId, value.methodKey, value.capturedAtEpoch,
        value.executionStartedAtEpoch, value.executionEndedAtEpoch, value.executionDurationMs,
        value.threadAllocatedBytesDelta, value.redactionMode, value.argsPreview.stream()
            .map(DebugApi::toApi).toList(), toApi(value.returnPreview), toApi(value.thrownPreview),
        value.truncatedAny, value.executionPaths);
  }

  private static CaptureRecord toApi(CaptureRecordView value) {
    if (value == null) return null;
    return new CaptureRecord(value.captureId, value.methodKey, value.capturedAtEpoch,
        value.executionStartedAtEpoch, value.executionEndedAtEpoch, value.executionDurationMs,
        value.threadAllocatedBytesDelta, value.redactionMode, value.args.stream()
            .map(DebugApi::toApi).toList(), toApi(value.returnValue), toApi(value.thrownValue),
        value.truncatedAny, value.executionPaths);
  }

  private static CaptureValue toApi(CaptureValueView value) {
    return value == null ? null : new CaptureValue(value.value, value.truncated, value.originalLength, value.redacted);
  }

  private static DebugApi.FailureTraceAnalysis toApi(
      com.nimbly.mcpjavadevtools.agent.failure.FailureTraceAnalysis value) {
    if (value == null) return null;
    return new DebugApi.FailureTraceAnalysis(toApi(value.fingerprint()), value.investigationCandidates().stream()
        .map(DebugApi::toApi).toList(), toApi(value.dependencyBoundary()), value.exceptionSections().stream()
        .map(DebugApi::toApi).toList(), value.reasons());
  }

  private static DebugApi.FailureExceptionSection toApi(
      com.nimbly.mcpjavadevtools.agent.failure.FailureExceptionSection value) {
    return new DebugApi.FailureExceptionSection(value.exceptionType(), value.suppressed(), value.elidedFrames(),
        value.frames().stream().map(DebugApi::toApi).toList());
  }

  private static DebugApi.FailureFingerprint toApi(
      com.nimbly.mcpjavadevtools.agent.failure.FailureFingerprint value) {
    return value == null ? null : new DebugApi.FailureFingerprint(value.exceptionType(), value.rootCauseType(),
        toApi(value.nearestApplicationFrame()), value.normalizedMessage(), value.complete(),
        value.incompletenessReasons());
  }

  private static DebugApi.FailureFrame toApi(
      com.nimbly.mcpjavadevtools.agent.failure.FailureFrame value) {
    return value == null ? null : new DebugApi.FailureFrame(value.className(), value.methodName(), value.sourceFile(),
        value.lineNumber(), value.ownership(), value.codeSource(), value.methodDescriptor(),
        value.codeSourceCandidates(), value.resolutionReason());
  }

  private static DebugApi.FailureComparison toApi(
      com.nimbly.mcpjavadevtools.agent.failure.FailureComparison value) {
    return new DebugApi.FailureComparison(value.outcome(), toApi(value.observedFingerprint()), value.reasons());
  }

  private static com.nimbly.mcpjavadevtools.agent.failure.FailureFingerprint toInternal(
      DebugApi.FailureFingerprint value) {
    if (value == null) return null;
    return new com.nimbly.mcpjavadevtools.agent.failure.FailureFingerprint(value.exceptionType(),
        value.rootCauseType(), toInternal(value.nearestApplicationFrame()), value.normalizedMessage(),
        value.complete(), value.incompletenessReasons());
  }

  private static com.nimbly.mcpjavadevtools.agent.failure.FailureFrame toInternal(
      DebugApi.FailureFrame value) {
    return value == null ? null : new com.nimbly.mcpjavadevtools.agent.failure.FailureFrame(value.className(),
        value.methodName(), value.sourceFile(), value.lineNumber(), value.ownership(), value.codeSource(),
        value.methodDescriptor(), value.codeSourceCandidates(), value.resolutionReason());
  }

  public static final class CapturePreview {
    public final boolean available;
    public final String captureId;
    public final String methodKey;
    public final long capturedAtEpoch;
    public final long executionStartedAtEpoch;
    public final long executionEndedAtEpoch;
    public final long executionDurationMs;
    public final Long threadAllocatedBytesDelta;
    public final String redactionMode;
    public final List<CaptureValue> argsPreview;
    public final CaptureValue returnPreview;
    public final CaptureValue thrownPreview;
    public final boolean truncatedAny;
    public final List<String> executionPaths;

    private CapturePreview(boolean available, String captureId, String methodKey, long capturedAtEpoch,
                           long executionStartedAtEpoch, long executionEndedAtEpoch, long executionDurationMs,
                           Long threadAllocatedBytesDelta, String redactionMode, List<CaptureValue> argsPreview,
                           CaptureValue returnPreview, CaptureValue thrownPreview, boolean truncatedAny,
                           List<String> executionPaths) {
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
  }

  public static final class CaptureRecord {
    public final String captureId;
    public final String methodKey;
    public final long capturedAtEpoch;
    public final long executionStartedAtEpoch;
    public final long executionEndedAtEpoch;
    public final long executionDurationMs;
    public final Long threadAllocatedBytesDelta;
    public final String redactionMode;
    public final List<CaptureValue> args;
    public final CaptureValue returnValue;
    public final CaptureValue thrownValue;
    public final boolean truncatedAny;
    public final List<String> executionPaths;

    private CaptureRecord(String captureId, String methodKey, long capturedAtEpoch, long executionStartedAtEpoch,
                          long executionEndedAtEpoch, long executionDurationMs, Long threadAllocatedBytesDelta,
                          String redactionMode, List<CaptureValue> args, CaptureValue returnValue,
                          CaptureValue thrownValue, boolean truncatedAny, List<String> executionPaths) {
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

  public record CaptureValue(String value, boolean truncated, int originalLength, boolean redacted) {}
  public record FailureFrame(String className, String methodName, String sourceFile, Integer lineNumber,
                             String ownership, String codeSource, String methodDescriptor,
                             List<String> codeSourceCandidates, String resolutionReason) {
    public String methodKey() { return className + "#" + methodName; }
    public String strictLineKey() { return lineNumber == null || lineNumber <= 0 ? null : methodKey() + ":" + lineNumber; }
  }
  public record FailureFingerprint(String exceptionType, String rootCauseType, FailureFrame nearestApplicationFrame,
                                   String normalizedMessage, boolean complete, List<String> incompletenessReasons) {}
  public record FailureExceptionSection(String exceptionType, boolean suppressed, boolean elidedFrames,
                                       List<FailureFrame> frames) {}
  public record FailureTraceAnalysis(FailureFingerprint fingerprint, List<FailureFrame> investigationCandidates,
                                     FailureFrame dependencyBoundary, List<FailureExceptionSection> exceptionSections,
                                     List<String> reasons) {}
  public record FailureComparison(String outcome, FailureFingerprint observedFingerprint, List<String> reasons) {}
}
