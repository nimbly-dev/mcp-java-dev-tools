package com.nimbly.mcpjavadevtools.agent.profiler;

import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStartRequest;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStateSnapshot;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStopRequest;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStopResult;
import one.profiler.AsyncProfiler;
import one.profiler.AsyncProfilerLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public final class AsyncProfilerProbeProfiler implements ProbeProfiler {
  private static final String PROVIDER = "async-profiler";
  private static final String DEFAULT_EVENT = "cpu";
  private static final String DEFAULT_OUTPUT_FORMAT = "jfr";
  private static final long STOP_OUTPUT_WAIT_TIMEOUT_MS = 5000L;
  private static final long STOP_OUTPUT_WAIT_INTERVAL_MS = 100L;

  private final Object lock = new Object();
  private final AsyncProfiler profiler;
  private final Path outputDirectory;
  private volatile ProfilerSession activeSession;
  private volatile ProfilerStateSnapshot lastState;

  private AsyncProfilerProbeProfiler(AsyncProfiler profiler, Path outputDirectory) {
    this.profiler = profiler;
    this.outputDirectory = outputDirectory;
    this.lastState = new ProfilerStateSnapshot(
        "idle",
        PROVIDER,
        true,
        "",
        null,
        null,
        null,
        null,
        "ready"
    );
  }

  public static boolean isSupported() {
    return AsyncProfilerLoader.isSupported();
  }

  public static AsyncProfilerProbeProfiler create(Path outputDirectory) {
    try {
      Path normalizedOutputDir = ProfilerPaths.ensureOutputDirectory(outputDirectory);
      AsyncProfiler loaded = AsyncProfilerLoader.load();
      return new AsyncProfilerProbeProfiler(loaded, normalizedOutputDir);
    } catch (IOException ex) {
      throw new IllegalStateException("async_profiler_load_failed:" + sanitizeDetail(ex.getMessage()), ex);
    }
  }

  @Override
  public ProfilerStateSnapshot state() {
    return lastState;
  }

  @Override
  public ProfilerStateSnapshot start(ProfilerStartRequest request) {
    synchronized (lock) {
      if (activeSession != null) {
        lastState = new ProfilerStateSnapshot(
            "running",
            PROVIDER,
            true,
            activeSession.sessionId,
            activeSession.startedAtEpochMs,
            activeSession.event,
            activeSession.intervalNanos,
            activeSession.outputPath.toString(),
            "profiler_session_already_running"
        );
        return lastState;
      }
      String sessionId = sanitizeSessionId(request == null ? null : request.sessionId());
      String event = sanitizeEvent(request == null ? null : request.event());
      Long intervalNanos = sanitizeInterval(request == null ? null : request.intervalNanos());
      String outputFormat = sanitizeOutputFormat(request == null ? null : request.outputFormat());
      Path outputPath = resolveOutputPath(sessionId, request == null ? null : request.outputPath(), outputFormat);
      String command = buildStartCommand(event, intervalNanos, outputPath, outputFormat);
      try {
        profiler.execute(command);
        long startedAtEpochMs = Instant.now().toEpochMilli();
        activeSession = new ProfilerSession(
            sessionId,
            startedAtEpochMs,
            event,
            intervalNanos,
            outputFormat,
            outputPath
        );
        lastState = new ProfilerStateSnapshot(
            "running",
            PROVIDER,
            true,
            sessionId,
            startedAtEpochMs,
            event,
            intervalNanos,
            outputPath.toString(),
            "running"
        );
      } catch (RuntimeException | IOException ex) {
        lastState = new ProfilerStateSnapshot(
            "failed",
            PROVIDER,
            true,
            sessionId,
            null,
            event,
            intervalNanos,
            outputPath.toString(),
            "profiler_start_failed:" + sanitizeDetail(ex.getMessage())
        );
      }
      return lastState;
    }
  }

  @Override
  public ProfilerStopResult stop(ProfilerStopRequest request) {
    synchronized (lock) {
      if (activeSession == null) {
        return new ProfilerStopResult(
            request == null ? "" : sanitizeSessionId(request.sessionId()),
            PROVIDER,
            "idle",
            true,
            null,
            request == null ? null : request.outputPath(),
            request == null ? null : request.outputFormat(),
            "profiler_not_running"
        );
      }
      if (request != null && request.sessionId() != null && !request.sessionId().isBlank()) {
        String requestedSessionId = sanitizeSessionId(request.sessionId());
        if (!activeSession.sessionId.equals(requestedSessionId)) {
          return new ProfilerStopResult(
              requestedSessionId,
              PROVIDER,
              "running",
              true,
              null,
              activeSession.outputPath.toString(),
              activeSession.outputFormat,
              "profiler_session_mismatch"
          );
        }
      }
      Path outputPath = activeSession.outputPath;
      String outputFormat = activeSession.outputFormat;
      String command = buildStopCommand();
      try {
        profiler.execute(command);
        boolean outputReady = waitForOutputFile(outputPath, STOP_OUTPUT_WAIT_TIMEOUT_MS, STOP_OUTPUT_WAIT_INTERVAL_MS);
        if (!outputReady) {
          lastState = new ProfilerStateSnapshot(
              "failed",
              PROVIDER,
              true,
              activeSession.sessionId,
              activeSession.startedAtEpochMs,
              activeSession.event,
              activeSession.intervalNanos,
              outputPath.toString(),
              "profiler_output_not_found_after_stop"
          );
          return new ProfilerStopResult(
              activeSession.sessionId,
              PROVIDER,
              "failed",
              true,
              null,
              outputPath.toString(),
              outputFormat,
              "profiler_output_not_found_after_stop"
          );
        }
        long stoppedAtEpochMs = Instant.now().toEpochMilli();
        String completedSessionId = activeSession.sessionId;
        activeSession = null;
        lastState = new ProfilerStateSnapshot(
            "completed",
            PROVIDER,
            true,
            completedSessionId,
            null,
            null,
            null,
            outputPath.toString(),
            "completed"
        );
        return new ProfilerStopResult(
            activeSessionIdOr(request),
            PROVIDER,
            "completed",
            true,
            stoppedAtEpochMs,
            outputPath.toString(),
            outputFormat,
            "completed"
        );
      } catch (RuntimeException | IOException ex) {
        lastState = new ProfilerStateSnapshot(
            "failed",
            PROVIDER,
            true,
            activeSession.sessionId,
            activeSession.startedAtEpochMs,
            activeSession.event,
            activeSession.intervalNanos,
            outputPath.toString(),
            "profiler_stop_failed:" + sanitizeDetail(ex.getMessage())
        );
        return new ProfilerStopResult(
            activeSession.sessionId,
            PROVIDER,
            "failed",
            true,
            null,
            outputPath.toString(),
            outputFormat,
            "profiler_stop_failed:" + sanitizeDetail(ex.getMessage())
        );
      }
    }
  }

  @Override
  public ProfilerStateSnapshot reset() {
    synchronized (lock) {
      if (activeSession != null) {
        lastState = new ProfilerStateSnapshot(
            "running",
            PROVIDER,
            true,
            activeSession.sessionId,
            activeSession.startedAtEpochMs,
            activeSession.event,
            activeSession.intervalNanos,
            activeSession.outputPath.toString(),
            "profiler_running_reset_denied"
        );
        return lastState;
      }
      lastState = new ProfilerStateSnapshot(
          "idle",
          PROVIDER,
          true,
          "",
          null,
          null,
          null,
          null,
          "ready"
      );
      return lastState;
    }
  }

  private static String buildStartCommand(String event, Long intervalNanos, Path outputPath, String outputFormat) {
    StringBuilder command = new StringBuilder("start,event=").append(event);
    if (intervalNanos != null && intervalNanos > 0) {
      command.append(",interval=").append(intervalNanos);
    }
    command.append(",file=").append(outputPath.toAbsolutePath().normalize());
    if (outputFormat != null && !outputFormat.isBlank()) {
      command.append(",").append(outputFormat);
    }
    return command.toString();
  }

  private static String buildStopCommand() {
    return "stop";
  }

  private Path resolveOutputPath(String sessionId, String requestedOutputPath, String outputFormat) {
    try {
      if (requestedOutputPath != null && !requestedOutputPath.isBlank()) {
        Path candidate = Path.of(requestedOutputPath.trim()).toAbsolutePath().normalize();
        Path parent = candidate.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        return candidate;
      }
      Files.createDirectories(outputDirectory);
      return outputDirectory.resolve(sessionId + "." + outputFormat).toAbsolutePath().normalize();
    } catch (IOException ex) {
      throw new IllegalStateException("profiler_output_path_unavailable:" + sanitizeDetail(ex.getMessage()), ex);
    }
  }

  private static String sanitizeSessionId(String raw) {
    if (raw == null || raw.isBlank()) {
      return "session-" + Instant.now().toEpochMilli();
    }
    String sanitized = raw.trim().replaceAll("[^A-Za-z0-9._-]", "-");
    if (sanitized.isBlank()) {
      return "session-" + Instant.now().toEpochMilli();
    }
    return sanitized;
  }

  private static String sanitizeEvent(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_EVENT;
    }
    return raw.trim();
  }

  private static Long sanitizeInterval(Long intervalNanos) {
    if (intervalNanos == null || intervalNanos <= 0) {
      return null;
    }
    return intervalNanos;
  }

  private static String sanitizeOutputFormat(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_OUTPUT_FORMAT;
    }
    String normalized = raw.trim().toLowerCase();
    if (!"jfr".equals(normalized)) {
      return DEFAULT_OUTPUT_FORMAT;
    }
    return normalized;
  }

  private static String activeSessionIdOr(ProfilerStopRequest request) {
    if (request == null || request.sessionId() == null) {
      return "";
    }
    return sanitizeSessionId(request.sessionId());
  }

  private static String sanitizeDetail(String detail) {
    if (detail == null || detail.isBlank()) {
      return "unknown";
    }
    return detail.trim().replace('\n', ' ').replace('\r', ' ');
  }

  private static boolean waitForOutputFile(Path outputPath, long timeoutMs, long intervalMs) {
    long deadline = System.currentTimeMillis() + Math.max(timeoutMs, intervalMs);
    while (System.currentTimeMillis() <= deadline) {
      if (Files.isRegularFile(outputPath)) {
        return true;
      }
      try {
        Thread.sleep(intervalMs);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return Files.isRegularFile(outputPath);
      }
    }
    return Files.isRegularFile(outputPath);
  }

  private static final class ProfilerSession {
    private final String sessionId;
    private final long startedAtEpochMs;
    private final String event;
    private final Long intervalNanos;
    private final String outputFormat;
    private final Path outputPath;

    private ProfilerSession(
        String sessionId,
        long startedAtEpochMs,
        String event,
        Long intervalNanos,
        String outputFormat,
        Path outputPath
    ) {
      this.sessionId = sessionId;
      this.startedAtEpochMs = startedAtEpochMs;
      this.event = event;
      this.intervalNanos = intervalNanos;
      this.outputFormat = outputFormat;
      this.outputPath = outputPath;
    }
  }
}
