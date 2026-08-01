package com.nimbly.mcpjavadevtools.agent.profiler.api;

import com.nimbly.mcpjavadevtools.agent.profiler.ProbeProfilerRegistry;
import com.nimbly.mcpjavadevtools.agent.profiler.ProfilerPaths;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStartRequest;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStateSnapshot;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStopRequest;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStopResult;
import java.nio.file.Path;

/** Stable profiler capability surface for bootstrap and HTTP consumers. */
public final class ProfilerApi {
  private ProfilerApi() {}

  public static void configureDefault(Path outputDirectory) {
    ProbeProfilerRegistry.configureDefault(outputDirectory);
  }

  public static Path resolveConfiguredOutputDirectory() {
    return ProfilerPaths.resolveConfiguredOutputDirectory();
  }

  public static State state() {
    return toApi(ProbeProfilerRegistry.active().state());
  }

  public static State start(StartRequest request) {
    return toApi(ProbeProfilerRegistry.active().start(
        new ProfilerStartRequest(
            request.sessionId(), request.event(), request.intervalNanos(), request.outputPath(), request.outputFormat())));
  }

  public static StopResult stop(StopRequest request) {
    return toApi(ProbeProfilerRegistry.active().stop(
        new ProfilerStopRequest(
            request.sessionId(), request.outputPath(), request.outputFormat())));
  }

  public static State reset() {
    return toApi(ProbeProfilerRegistry.active().reset());
  }

  private static State toApi(ProfilerStateSnapshot value) {
    return new State(value.status(), value.provider(), value.supported(), value.sessionId(),
        value.startedAtEpochMs(), value.event(), value.intervalNanos(), value.outputPath(), value.detail());
  }

  private static StopResult toApi(ProfilerStopResult value) {
    return new StopResult(value.sessionId(), value.provider(), value.status(), value.supported(),
        value.stoppedAtEpochMs(), value.outputPath(), value.outputFormat(), value.detail());
  }

  public record StartRequest(String sessionId, String event, Long intervalNanos, String outputPath,
                             String outputFormat) {}
  public record StopRequest(String sessionId, String outputPath, String outputFormat) {}
  public record State(String status, String provider, boolean supported, String sessionId,
                      Long startedAtEpochMs, String event, Long intervalNanos, String outputPath,
                      String detail) {}
  public record StopResult(String sessionId, String provider, String status, boolean supported,
                           Long stoppedAtEpochMs, String outputPath, String outputFormat, String detail) {}
}
