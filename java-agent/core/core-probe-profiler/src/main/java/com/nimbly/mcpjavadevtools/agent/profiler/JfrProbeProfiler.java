package com.nimbly.mcpjavadevtools.agent.profiler;

import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStartRequest;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStateSnapshot;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStopRequest;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStopResult;
import jdk.jfr.Recording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

public final class JfrProbeProfiler implements ProbeProfiler {
  private static final String PROVIDER = "jfr";
  private static final String DEFAULT_EVENT = "cpu";
  private static final String DEFAULT_OUTPUT_FORMAT = "jfr";
  private static final long DEFAULT_INTERVAL_NANOS = 10_000_000L;
  private static final long STOP_OUTPUT_WAIT_TIMEOUT_MS = 5000L;
  private static final long STOP_OUTPUT_WAIT_INTERVAL_MS = 100L;

  private final Object lock = new Object();
  private final Path outputDirectory;
  private volatile ProfilerSession activeSession;
  private volatile ProfilerStateSnapshot lastState;

  private JfrProbeProfiler(Path outputDirectory) {
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
    try {
      Class.forName("jdk.jfr.Recording", false, JfrProbeProfiler.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException | LinkageError ex) {
      return false;
    }
  }

  public static JfrProbeProfiler create(Path outputDirectory) {
    if (!isSupported()) {
      throw new IllegalStateException("jfr_provider_unavailable");
    }
    return new JfrProbeProfiler(outputDirectory);
  }

  @Override
  public ProfilerStateSnapshot state() {
    return lastState;
  }

  @Override
  public ProfilerStateSnapshot start(ProfilerStartRequest request) {
    synchronized (lock) {
      if (activeSession != null) {
        lastState = runningState("profiler_session_already_running");
        return lastState;
      }

      String sessionId = sanitizeSessionId(request == null ? null : request.sessionId());
      String event = sanitizeEvent(request == null ? null : request.event());
      Long intervalNanos = sanitizeInterval(request == null ? null : request.intervalNanos());
      String outputFormat = sanitizeOutputFormat(request == null ? null : request.outputFormat());
      if (!isSupportedEvent(event)) {
        lastState = new ProfilerStateSnapshot(
            "failed",
            PROVIDER,
            true,
            sessionId,
            null,
            event,
            intervalNanos,
            null,
            "jfr_event_unsupported:" + event
        );
        return lastState;
      }

      Path outputPath = resolveOutputPath(sessionId, request == null ? null : request.outputPath(), outputFormat);
      Recording recording = null;
      try {
        recording = new Recording();
        configureRecording(recording, intervalNanos);
        recording.setDestination(outputPath);
        recording.start();
        long startedAtEpochMs = Instant.now().toEpochMilli();
        activeSession = new ProfilerSession(
            sessionId,
            startedAtEpochMs,
            event,
            intervalNanos,
            outputFormat,
            outputPath,
            recording
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
      } catch (IOException | RuntimeException ex) {
        closeQuietly(recording);
        lastState = new ProfilerStateSnapshot(
            "failed",
            PROVIDER,
            true,
            sessionId,
            null,
            event,
            intervalNanos,
            outputPath.toString(),
            "jfr_start_failed:" + sanitizeDetail(ex.getMessage())
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

      ProfilerSession session = activeSession;
      try {
        session.recording.stop();
        session.recording.close();
        boolean outputReady = waitForOutputFile(
            session.outputPath,
            STOP_OUTPUT_WAIT_TIMEOUT_MS,
            STOP_OUTPUT_WAIT_INTERVAL_MS
        );
        if (!outputReady) {
          lastState = new ProfilerStateSnapshot(
              "failed",
              PROVIDER,
              true,
              session.sessionId,
              session.startedAtEpochMs,
              session.event,
              session.intervalNanos,
              session.outputPath.toString(),
              "profiler_output_not_found_after_stop"
          );
          return new ProfilerStopResult(
              session.sessionId,
              PROVIDER,
              "failed",
              true,
              null,
              session.outputPath.toString(),
              session.outputFormat,
              "profiler_output_not_found_after_stop"
          );
        }

        long stoppedAtEpochMs = Instant.now().toEpochMilli();
        activeSession = null;
        lastState = new ProfilerStateSnapshot(
            "completed",
            PROVIDER,
            true,
            session.sessionId,
            null,
            null,
            null,
            session.outputPath.toString(),
            "completed"
        );
        return new ProfilerStopResult(
            session.sessionId,
            PROVIDER,
            "completed",
            true,
            stoppedAtEpochMs,
            session.outputPath.toString(),
            session.outputFormat,
            "completed"
        );
      } catch (RuntimeException ex) {
        closeQuietly(session.recording);
        lastState = new ProfilerStateSnapshot(
            "failed",
            PROVIDER,
            true,
            session.sessionId,
            session.startedAtEpochMs,
            session.event,
            session.intervalNanos,
            session.outputPath.toString(),
            "jfr_stop_failed:" + sanitizeDetail(ex.getMessage())
        );
        return new ProfilerStopResult(
            session.sessionId,
            PROVIDER,
            "failed",
            true,
            null,
            session.outputPath.toString(),
            session.outputFormat,
            "jfr_stop_failed:" + sanitizeDetail(ex.getMessage())
        );
      }
    }
  }

  @Override
  public ProfilerStateSnapshot reset() {
    synchronized (lock) {
      if (activeSession != null) {
        lastState = runningState("profiler_running_reset_denied");
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

  private ProfilerStateSnapshot runningState(String detail) {
    return new ProfilerStateSnapshot(
        "running",
        PROVIDER,
        true,
        activeSession.sessionId,
        activeSession.startedAtEpochMs,
        activeSession.event,
        activeSession.intervalNanos,
        activeSession.outputPath.toString(),
        detail
    );
  }

  private static void configureRecording(Recording recording, Long intervalNanos) {
    Duration period = Duration.ofNanos(intervalNanos == null ? DEFAULT_INTERVAL_NANOS : intervalNanos);
    recording.enable("jdk.ExecutionSample").withPeriod(period).withStackTrace();
    try {
      recording.enable("jdk.NativeMethodSample").withPeriod(period).withStackTrace();
    } catch (IllegalArgumentException ignored) {
      // ExecutionSample is the required cross-platform event; native samples are optional.
    }
  }

  private static boolean isSupportedEvent(String event) {
    return "cpu".equals(event) || "wall".equals(event);
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
      throw new IllegalStateException("jfr_output_path_unavailable:" + sanitizeDetail(ex.getMessage()), ex);
    }
  }

  private static String sanitizeSessionId(String raw) {
    if (raw == null || raw.isBlank()) {
      return "session-" + Instant.now().toEpochMilli();
    }
    String sanitized = raw.trim().replaceAll("[^A-Za-z0-9._-]", "-");
    return sanitized.isBlank() ? "session-" + Instant.now().toEpochMilli() : sanitized;
  }

  private static String sanitizeEvent(String raw) {
    return raw == null || raw.isBlank() ? DEFAULT_EVENT : raw.trim().toLowerCase();
  }

  private static Long sanitizeInterval(Long intervalNanos) {
    return intervalNanos == null || intervalNanos <= 0 ? null : intervalNanos;
  }

  private static String sanitizeOutputFormat(String raw) {
    if (raw == null || raw.isBlank()) return DEFAULT_OUTPUT_FORMAT;
    return "jfr".equalsIgnoreCase(raw.trim()) ? DEFAULT_OUTPUT_FORMAT : DEFAULT_OUTPUT_FORMAT;
  }

  private static String sanitizeDetail(String detail) {
    if (detail == null || detail.isBlank()) return "unknown";
    return detail.trim().replace('\n', ' ').replace('\r', ' ');
  }

  private static boolean waitForOutputFile(Path outputPath, long timeoutMs, long intervalMs) {
    long deadline = System.currentTimeMillis() + Math.max(timeoutMs, intervalMs);
    while (System.currentTimeMillis() <= deadline) {
      if (Files.isRegularFile(outputPath)) return true;
      try {
        Thread.sleep(intervalMs);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return Files.isRegularFile(outputPath);
      }
    }
    return Files.isRegularFile(outputPath);
  }

  private static void closeQuietly(Recording recording) {
    if (recording == null) return;
    try {
      recording.close();
    } catch (RuntimeException ignored) {
      // Preserve the original provider failure detail.
    }
  }

  private static final class ProfilerSession {
    private final String sessionId;
    private final long startedAtEpochMs;
    private final String event;
    private final Long intervalNanos;
    private final String outputFormat;
    private final Path outputPath;
    private final Recording recording;

    private ProfilerSession(
        String sessionId,
        long startedAtEpochMs,
        String event,
        Long intervalNanos,
        String outputFormat,
        Path outputPath,
        Recording recording
    ) {
      this.sessionId = sessionId;
      this.startedAtEpochMs = startedAtEpochMs;
      this.event = event;
      this.intervalNanos = intervalNanos;
      this.outputFormat = outputFormat;
      this.outputPath = outputPath;
      this.recording = recording;
    }
  }
}
