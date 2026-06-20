package com.nimbly.mcpjavadevtools.agent.capture;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class ProbeCaptureStore {
  private static final Object CAPTURE_LOCK = new Object();
  private static final LinkedHashMap<String, Deque<CaptureEntry>> CAPTURE_BY_METHOD_KEY =
      new LinkedHashMap<>(16, 0.75f, true);
  private static final LinkedHashMap<String, CaptureEntry> CAPTURE_BY_ID = new LinkedHashMap<>();
  private static final AtomicLong CAPTURE_SEQ = new AtomicLong(0L);

  private static volatile boolean captureEnabled = true;
  private static volatile int captureMaxKeys = 1000;
  private static volatile int captureMaxArgs = 32;
  private static volatile int captureMethodBufferSize = 3;
  private static volatile int capturePreviewMaxChars = 1024;
  private static volatile int captureStoredMaxChars = 16384;
  private static volatile String captureRedactionMode = "basic";

  private ProbeCaptureStore() {}

  public static void configureCapture(
      boolean captureEnabled,
      int captureMaxKeys,
      int captureMaxArgs,
      int captureMethodBufferSize,
      int capturePreviewMaxChars,
      int captureStoredMaxChars,
      String captureRedactionMode
  ) {
    ProbeCaptureStore.captureEnabled = captureEnabled;
    ProbeCaptureStore.captureMaxKeys = clamp(captureMaxKeys, 10, 20_000, 1000);
    ProbeCaptureStore.captureMaxArgs = clamp(captureMaxArgs, 1, 512, 32);
    ProbeCaptureStore.captureMethodBufferSize = clamp(captureMethodBufferSize, 1, 32, 3);
    ProbeCaptureStore.capturePreviewMaxChars = clamp(capturePreviewMaxChars, 64, 65_536, 1024);
    ProbeCaptureStore.captureStoredMaxChars = clamp(captureStoredMaxChars, 256, 524_288, 16_384);
    if (ProbeCaptureStore.captureStoredMaxChars < ProbeCaptureStore.capturePreviewMaxChars) {
      ProbeCaptureStore.captureStoredMaxChars = ProbeCaptureStore.capturePreviewMaxChars;
    }
    ProbeCaptureStore.captureRedactionMode =
        "off".equalsIgnoreCase(captureRedactionMode) ? "off" : "basic";
  }

  public static void configureExecutionPathScope(List<String> includePatterns, List<String> excludePatterns) {
    ExecutionPathCollector.configureScope(includePatterns, excludePatterns);
  }

  public static boolean isCaptureEnabled() {
    return captureEnabled;
  }

  public static String getCaptureRedactionMode() {
    return captureRedactionMode;
  }

  public static void captureByClassMethod(
      String dottedClassName,
      String methodName,
      Object[] allArguments,
      Object returnValue,
      Throwable thrown,
      long executionStartedAtEpoch,
      long executionEndedAtEpoch,
      long threadAllocatedBytesAtEnter
  ) {
    if (!captureEnabled) return;
    if (dottedClassName == null || dottedClassName.isBlank()) return;
    if (methodName == null || methodName.isBlank()) return;

    String methodKey = dottedClassName + "#" + methodName;
    String captureId = Long.toHexString(CAPTURE_SEQ.incrementAndGet());
    long normalizedExecutionEndedAtEpoch = executionEndedAtEpoch > 0
        ? executionEndedAtEpoch
        : System.currentTimeMillis();
    long normalizedExecutionStartedAtEpoch = executionStartedAtEpoch > 0
        ? executionStartedAtEpoch
        : normalizedExecutionEndedAtEpoch;
    if (normalizedExecutionEndedAtEpoch < normalizedExecutionStartedAtEpoch) {
      normalizedExecutionEndedAtEpoch = normalizedExecutionStartedAtEpoch;
    }
    long executionDurationMs = normalizedExecutionEndedAtEpoch - normalizedExecutionStartedAtEpoch;
    long capturedAtEpoch = normalizedExecutionEndedAtEpoch;
    Long threadAllocatedBytesDelta = null;
    long threadAllocatedBytesAtExit = ThreadAllocationMetrics.currentThreadAllocatedBytes();
    if (threadAllocatedBytesAtEnter >= 0L && threadAllocatedBytesAtExit >= 0L) {
      long delta = threadAllocatedBytesAtExit - threadAllocatedBytesAtEnter;
      threadAllocatedBytesDelta = delta >= 0L ? delta : 0L;
    }

    List<CaptureValue> capturedArgs = CaptureValueSerializer.serializeArguments(
        allArguments,
        captureMaxArgs,
        captureRedactionMode,
        captureStoredMaxChars
    );
    CaptureValue capturedReturn = CaptureValueSerializer.serializeSingleValue(
        returnValue,
        null,
        captureRedactionMode,
        captureStoredMaxChars
    );
    CaptureValue capturedThrown = thrown == null
        ? null
        : CaptureValueSerializer.serializeSingleValue(
            thrown,
            null,
            captureRedactionMode,
            captureStoredMaxChars
        );
    List<String> executionPaths = ExecutionPathCollector.collectExecutionPaths(dottedClassName, methodName);

    CaptureEntry entry = new CaptureEntry(
        captureId,
        methodKey,
        capturedAtEpoch,
        normalizedExecutionStartedAtEpoch,
        normalizedExecutionEndedAtEpoch,
        executionDurationMs,
        threadAllocatedBytesDelta,
        capturedArgs,
        capturedReturn,
        capturedThrown,
        executionPaths,
        captureRedactionMode
    );

    synchronized (CAPTURE_LOCK) {
      Deque<CaptureEntry> methodCaptures = CAPTURE_BY_METHOD_KEY.get(methodKey);
      if (methodCaptures == null) {
        methodCaptures = new ArrayDeque<>();
        CAPTURE_BY_METHOD_KEY.put(methodKey, methodCaptures);
      }
      methodCaptures.addLast(entry);
      CAPTURE_BY_ID.put(captureId, entry);
      while (methodCaptures.size() > captureMethodBufferSize) {
        CaptureEntry removed = methodCaptures.removeFirst();
        CAPTURE_BY_ID.remove(removed.captureId);
      }
      evictCapturesIfNeeded();
    }
  }

  public static CapturePreviewView getCapturePreviewForKey(String key) {
    String methodKey = CaptureKeyParser.toMethodKey(key);
    if (methodKey == null) return CapturePreviewView.unavailable(captureRedactionMode);
    synchronized (CAPTURE_LOCK) {
      Deque<CaptureEntry> captures = CAPTURE_BY_METHOD_KEY.get(methodKey);
      if (captures == null || captures.isEmpty()) {
        return CapturePreviewView.unavailable(captureRedactionMode);
      }
      CaptureEntry entry = captures.peekLast();
      if (entry == null) return CapturePreviewView.unavailable(captureRedactionMode);
      return entry.toPreview(capturePreviewMaxChars);
    }
  }

  public static CaptureRecordView getCaptureById(String captureId) {
    if (captureId == null || captureId.isBlank()) return null;
    synchronized (CAPTURE_LOCK) {
      CaptureEntry entry = CAPTURE_BY_ID.get(captureId.trim());
      if (entry == null) return null;
      return entry.toRecord();
    }
  }

  public static long currentThreadAllocatedBytes() {
    return ThreadAllocationMetrics.currentThreadAllocatedBytes();
  }

  public static void resetByKey(String key) {
    String methodKey = CaptureKeyParser.toMethodKey(key);
    if (methodKey == null) return;
    synchronized (CAPTURE_LOCK) {
      Deque<CaptureEntry> removed = CAPTURE_BY_METHOD_KEY.remove(methodKey);
      if (removed != null) {
        for (CaptureEntry capture : removed) {
          CAPTURE_BY_ID.remove(capture.captureId);
        }
      }
    }
  }

  private static void evictCapturesIfNeeded() {
    while (CAPTURE_BY_METHOD_KEY.size() > captureMaxKeys) {
      Map.Entry<String, Deque<CaptureEntry>> eldest = CAPTURE_BY_METHOD_KEY.entrySet().iterator().next();
      Deque<CaptureEntry> removed = CAPTURE_BY_METHOD_KEY.remove(eldest.getKey());
      if (removed != null) {
        for (CaptureEntry capture : removed) {
          CAPTURE_BY_ID.remove(capture.captureId);
        }
      }
    }
  }

  private static int clamp(int value, int min, int max, int defaultValue) {
    if (value < min || value > max) return defaultValue;
    return value;
  }
}

