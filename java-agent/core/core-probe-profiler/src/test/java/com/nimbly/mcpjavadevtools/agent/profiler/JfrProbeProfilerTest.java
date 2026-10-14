package com.nimbly.mcpjavadevtools.agent.profiler;

import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStartRequest;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStateSnapshot;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStopRequest;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStopResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JfrProbeProfilerTest {
  @TempDir
  Path tempDirectory;

  @Test
  void wallSamplesObserveSleepingThreadsAndUnregisterAfterStop() throws Exception {
    JfrProbeProfiler profiler = JfrProbeProfiler.create(tempDirectory);
    assertEquals("running", profiler.start(new ProfilerStartRequest("jfr", "sleep", "wall", 1_000_000L, null, "jfr")).status());
    sleepCalibration();
    ProfilerStopResult stopped = profiler.stop(new ProfilerStopRequest("sleep", null, "jfr"));
    List<RecordedEvent> events = RecordingFile.readAllEvents(Path.of(stopped.outputPath()));
    java.util.Map<Long, String> stacks = new java.util.HashMap<>();
    events.stream().filter(event -> event.getEventType().getName().equals("mcpjvm.WallClockStack"))
        .forEach(event -> stacks.put(event.getLong("stackId"), decodedStack(event)));
    double estimatedMs = events.stream().filter(event -> event.getEventType().getName().equals("mcpjvm.WallClockSample"))
        .filter(event -> stacks.get(event.getLong("stackId")).contains("#sleepCalibration"))
        .mapToDouble(event -> event.getLong("sampleDurationNanos") / 1_000_000.0).sum();
    assertTrue(estimatedMs >= 1000 && estimatedMs <= 4500, "sleep estimate: " + estimatedMs);
    RecordedEvent status = events.stream().filter(event -> event.getEventType().getName().equals("mcpjvm.WallClockSamplingStatus"))
        .findFirst().orElseThrow();
    assertTrue(status.getBoolean("complete"));
    assertEquals(100_000_000L, status.getLong("intervalNanos"));
    assertTrue(status.getInt("availableProcessors") > 0);
    try (Recording recording = new Recording()) {
      recording.enable(JfrWallClockSampler.WallSample.class);
      recording.start();
      Thread.sleep(100);
      recording.stop();
      Path after = tempDirectory.resolve("after.jfr");
      recording.dump(after);
      assertTrue(RecordingFile.readAllEvents(after).stream()
          .noneMatch(event -> event.getEventType().getName().equals("mcpjvm.WallClockSample")));
    }
  }

  private static void sleepCalibration() throws InterruptedException {
    Thread.sleep(3000);
  }

  private static String decodedStack(RecordedEvent event) {
    byte[] encoded = java.util.Base64.getDecoder().decode(event.getString("encodedFrames"));
    try (var gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(encoded))) {
      return new String(gzip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  @Test
  void startsStopsAndWritesJfrOutput() throws Exception {
    JfrProbeProfiler profiler = JfrProbeProfiler.create(tempDirectory);

    ProfilerStateSnapshot started = profiler.start(
        new ProfilerStartRequest("jfr", "jfr-session", "wall", 1_000_000L, null, "jfr")
    );

    assertEquals("running", started.status());
    assertEquals("jfr", started.provider());
    assertTrue(started.supported());

    ProfilerStopResult stopped = profiler.stop(new ProfilerStopRequest("jfr-session", null, "jfr"));

    assertEquals("completed", stopped.status());
    assertEquals("jfr", stopped.provider());
    assertTrue(Files.isRegularFile(Path.of(stopped.outputPath())));
    assertTrue(Files.size(Path.of(stopped.outputPath())) > 0L);
    assertEquals("idle", profiler.reset().status());
  }

  @Test
  void rejectsConcurrentSessionAndPreservesActiveSession() {
    JfrProbeProfiler profiler = JfrProbeProfiler.create(tempDirectory);

    ProfilerStateSnapshot first = profiler.start(
        new ProfilerStartRequest("jfr", "first", "cpu", null, null, "jfr")
    );
    ProfilerStateSnapshot second = profiler.start(
        new ProfilerStartRequest("jfr", "second", "cpu", null, null, "jfr")
    );

    assertEquals("running", first.status());
    assertEquals("first", second.sessionId());
    assertEquals("profiler_session_already_running", second.detail());

    profiler.stop(new ProfilerStopRequest("first", null, "jfr"));
  }

  @Test
  void rejectsUnsupportedJfrEventDeterministically() {
    JfrProbeProfiler profiler = JfrProbeProfiler.create(tempDirectory);

    ProfilerStateSnapshot state = profiler.start(
        new ProfilerStartRequest("jfr", "allocation", "alloc", null, null, "jfr")
    );

    assertEquals("failed", state.status());
    assertTrue(state.supported());
    assertEquals("jfr_event_unsupported:alloc", state.detail());
    assertFalse(Files.exists(tempDirectory.resolve("allocation.jfr")));
  }
}
