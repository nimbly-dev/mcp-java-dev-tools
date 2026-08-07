package com.nimbly.mcpjavadevtools.agent.profiler;

import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStartRequest;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStateSnapshot;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStopRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeProfilerRegistryTest {
  @TempDir
  Path tempDirectory;

  @Test
  void autoSelectsAsyncProfilerOrJfrByCapability() {
    ProbeProfilerRegistry.configureDefault(tempDirectory);

    ProfilerStateSnapshot state = ProbeProfilerRegistry.start(
        new ProfilerStartRequest("auto", "auto-session", "cpu", null, null, "jfr")
    );

    String expectedProvider = AsyncProfilerProbeProfiler.isSupported() ? "async-profiler" : "jfr";
    assertEquals(expectedProvider, state.provider());
    assertTrue(state.supported());

    ProbeProfilerRegistry.active().stop(new ProfilerStopRequest("auto-session", null, "jfr"));
  }

  @Test
  void explicitJfrRemainsSelectable() {
    ProbeProfilerRegistry.configureDefault(tempDirectory);

    ProfilerStateSnapshot state = ProbeProfilerRegistry.start(
        new ProfilerStartRequest("jfr", "explicit-jfr", "cpu", null, null, "jfr")
    );

    assertEquals("jfr", state.provider());
    assertTrue(state.supported());
    ProbeProfilerRegistry.active().stop(new ProfilerStopRequest("explicit-jfr", null, "jfr"));
  }
}
