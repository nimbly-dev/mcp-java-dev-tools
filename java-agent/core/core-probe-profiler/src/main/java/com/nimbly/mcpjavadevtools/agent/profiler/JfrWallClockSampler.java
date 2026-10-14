package com.nimbly.mcpjavadevtools.agent.profiler;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;

/** Bounded platform-thread wall snapshots owned by one JFR session. No target-method instrumentation. */
class JfrWallClockSampler implements AutoCloseable {
  private static final long MIN_PERIOD_NANOS = 100_000_000L;
  private static final int MAX_THREADS = 256;
  private static final int MAX_DEPTH = 64;
  private static final int MAX_EVENTS = 80_000;
  private static final int MAX_STACK_BYTES = 512_000;
  private static final int MAX_RAW_STACK_BYTES = 4_194_304;
  private static final int MAX_STACK_SIZE_BYTES = 16_384;
  private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
  private final Runnable hook = this::sample;
  private final long periodNanos;
  private final String sessionId;
  private final Map<String, Long> stacks = new HashMap<>();
  private int stackBytes;
  private int rawStackBytes;
  private long startedNanos;
  private ThreadInfo[] previous = new ThreadInfo[0];
  private long lastTick;
  private long initialDelayNanos;
  private long samplerThreadId;
  private int events;
  private boolean complete = true;
  private boolean closed;
  private boolean active;

  JfrWallClockSampler(Recording recording, Long requestedPeriod, String sessionId) {
    this.sessionId = sessionId;
    periodNanos = Math.min(1_000_000_000L, Math.max(MIN_PERIOD_NANOS, requestedPeriod == null ? MIN_PERIOD_NANOS : requestedPeriod));
    FlightRecorder.addPeriodicEvent(WallSample.class, hook);
    try {
      recording.enable(WallSample.class).withPeriod(Duration.ofNanos(periodNanos)).withoutStackTrace();
      recording.enable(WallStatus.class).withoutStackTrace();
      recording.enable(WallStack.class).withoutStackTrace();
    } catch (RuntimeException exception) {
      FlightRecorder.removePeriodicEvent(hook);
      throw exception;
    }
  }

  synchronized void activate() {
    startedNanos = System.nanoTime();
    active = true;
  }

  private synchronized void sample() {
    if (!active || closed || !complete) {
      return;
    }
    try {
      long now = System.nanoTime();
      if (lastTick != 0) {
        emit(now - lastTick);
      } else {
        initialDelayNanos = now - startedNanos;
        samplerThreadId = Thread.currentThread().threadId();
      }
      long[] ids = threads.getAllThreadIds();
      if (ids.length > MAX_THREADS) {
        complete = false;
        return;
      }
      previous = threads.getThreadInfo(ids, MAX_DEPTH);
      lastTick = now;
    } catch (RuntimeException exception) {
      complete = false;
    }
  }

  private void emit(long elapsed) {
    if (elapsed <= 0 || elapsed > Math.max(1_000_000_000L, periodNanos * 5)) {
      complete = false;
      return;
    }
    for (ThreadInfo thread : previous) {
      if (thread == null || thread.getThreadId() == samplerThreadId || thread.getStackTrace().length == 0) {
        continue;
      }
      if (++events > MAX_EVENTS) {
        complete = false;
        return;
      }
      WallSample event = new WallSample();
      event.sessionId = sessionId;
      event.sampledThreadId = thread.getThreadId();
      event.sampleDurationNanos = elapsed;
      event.stackId = stackId(thread.getStackTrace());
      if (!complete) {
        return;
      }
      event.commit();
    }
  }

  private long stackId(StackTraceElement[] stack) {
    String frames = frames(stack);
    Long known = stacks.get(frames);
    if (known != null) {
      return known;
    }
    byte[] raw = frames.getBytes(StandardCharsets.UTF_8);
    rawStackBytes += raw.length;
    if (raw.length > MAX_STACK_SIZE_BYTES || rawStackBytes > MAX_RAW_STACK_BYTES) {
      complete = false;
      return 0;
    }
    String encoded = encode(raw);
    stackBytes += encoded.length();
    if (stackBytes > MAX_STACK_BYTES) {
      complete = false;
      return 0;
    }
    long id = stacks.size() + 1L;
    stacks.put(frames, id);
    WallStack definition = new WallStack();
    definition.sessionId = sessionId;
    definition.stackId = id;
    definition.encodedFrames = encoded;
    definition.commit();
    return id;
  }

  private static String encode(byte[] frames) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
      gzip.write(frames);
    } catch (IOException exception) {
      throw new IllegalStateException("wall_stack_encoding_failed", exception);
    }
    return Base64.getEncoder().encodeToString(bytes.toByteArray());
  }

  private static String frames(StackTraceElement[] stack) {
    StringBuilder frames = new StringBuilder();
    for (StackTraceElement frame : stack) {
      if (!frames.isEmpty()) {
        frames.append('\n');
      }
      frames.append(frame.getClassName()).append('#').append(frame.getMethodName());
    }
    return frames.toString();
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    FlightRecorder.removePeriodicEvent(hook);
    if (complete && lastTick != 0) {
      emit(System.nanoTime() - lastTick);
    }
    if (lastTick == 0) {
      complete = false;
    }
    closed = true;
    previous = new ThreadInfo[0];
    stacks.clear();
    WallStatus status = new WallStatus();
    status.sessionId = sessionId;
    status.complete = complete;
    status.intervalNanos = periodNanos;
    status.availableProcessors = Runtime.getRuntime().availableProcessors();
    status.initialDelayNanos = initialDelayNanos;
    status.commit();
  }

  @Name("mcpjvm.WallClockSample")
  @Period("100 ms")
  @StackTrace(false)
  static class WallSample extends Event {
    String sessionId;
    long sampledThreadId;
    long sampleDurationNanos;
    long stackId;
  }

  @Name("mcpjvm.WallClockStack")
  @StackTrace(false)
  static class WallStack extends Event {
    String sessionId;
    long stackId;
    String encodedFrames;
  }

  @Name("mcpjvm.WallClockSamplingStatus")
  @StackTrace(false)
  static class WallStatus extends Event {
    String sessionId;
    boolean complete;
    long intervalNanos;
    int availableProcessors;
    long initialDelayNanos;
  }
}
