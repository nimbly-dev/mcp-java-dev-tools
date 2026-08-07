package com.nimbly.mcpjavadevtools.agent.profiler;

import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStartRequest;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStateSnapshot;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStopRequest;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStopResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JfrProbeProfilerTest {
  @TempDir
  Path tempDirectory;

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
