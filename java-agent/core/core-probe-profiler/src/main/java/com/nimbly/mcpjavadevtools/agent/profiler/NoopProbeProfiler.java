package com.nimbly.mcpjavadevtools.agent.profiler;

import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStartRequest;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStateSnapshot;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStopRequest;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStopResult;

public final class NoopProbeProfiler implements ProbeProfiler {
  private static final String PROVIDER = "async-profiler";
  private final String detail;

  public NoopProbeProfiler(String detail) {
    this.detail = detail == null || detail.isBlank() ? "profiler_not_configured" : detail.trim();
  }

  @Override
  public ProfilerStateSnapshot state() {
    return disabledState();
  }

  @Override
  public ProfilerStateSnapshot start(ProfilerStartRequest request) {
    return disabledState();
  }

  @Override
  public ProfilerStopResult stop(ProfilerStopRequest request) {
    return new ProfilerStopResult(
        request == null || request.sessionId() == null ? "" : request.sessionId().trim(),
        PROVIDER,
        "disabled",
        false,
        null,
        request == null ? null : request.outputPath(),
        request == null ? null : request.outputFormat(),
        detail
    );
  }

  @Override
  public ProfilerStateSnapshot reset() {
    return disabledState();
  }

  private ProfilerStateSnapshot disabledState() {
    return new ProfilerStateSnapshot(
        "disabled",
        PROVIDER,
        false,
        "",
        null,
        null,
        null,
        null,
        detail
    );
  }
}
