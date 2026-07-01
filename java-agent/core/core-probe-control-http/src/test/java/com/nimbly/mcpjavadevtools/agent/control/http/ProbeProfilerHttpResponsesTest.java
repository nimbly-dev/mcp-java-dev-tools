package com.nimbly.mcpjavadevtools.agent.control.http;

import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStateSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeProfilerHttpResponsesTest {

  @Test
  void unsupportedStartFailsClosedWithConflict() {
    ProfilerStateSnapshot state = new ProfilerStateSnapshot(
        "disabled",
        "async-profiler",
        false,
        "",
        null,
        null,
        null,
        null,
        "profiler_unsupported_platform"
    );

    assertTrue(ProbeProfilerHttpResponses.shouldFailClosedOnStart(state));
    assertEquals(409, ProbeProfilerHttpResponses.startStatusCode(state));
    assertEquals(
        "profiler_unsupported_platform",
        ProbeProfilerHttpResponses.startErrorEnvelope(state).error()
    );
  }

  @Test
  void failedStartFailsClosedWithServerError() {
    ProfilerStateSnapshot state = new ProfilerStateSnapshot(
        "failed",
        "async-profiler",
        true,
        "session-1",
        null,
        "wall",
        null,
        "C:/tmp/session-1.jfr",
        "profiler_start_failed:load_error"
    );

    assertTrue(ProbeProfilerHttpResponses.shouldFailClosedOnStart(state));
    assertEquals(500, ProbeProfilerHttpResponses.startStatusCode(state));
    assertEquals(
        "profiler_start_failed:load_error",
        ProbeProfilerHttpResponses.startErrorEnvelope(state).error()
    );
  }

  @Test
  void runningStartRemainsSuccessShaped() {
    ProfilerStateSnapshot state = new ProfilerStateSnapshot(
        "running",
        "async-profiler",
        true,
        "session-1",
        1L,
        "wall",
        null,
        "C:/tmp/session-1.jfr",
        "running"
    );

    assertFalse(ProbeProfilerHttpResponses.shouldFailClosedOnStart(state));
  }
}
