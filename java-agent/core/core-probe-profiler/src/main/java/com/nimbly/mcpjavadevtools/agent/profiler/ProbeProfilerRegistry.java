package com.nimbly.mcpjavadevtools.agent.profiler;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

public final class ProbeProfilerRegistry {
  private static final AtomicReference<ProbeProfiler> ACTIVE =
      new AtomicReference<>(new NoopProbeProfiler("profiler_uninitialized"));

  private ProbeProfilerRegistry() {}

  public static void configureDefault(Path outputDirectory) {
    ACTIVE.set(createDefault(outputDirectory));
  }

  public static ProbeProfiler active() {
    return ACTIVE.get();
  }

  private static ProbeProfiler createDefault(Path outputDirectory) {
    if (!AsyncProfilerProbeProfiler.isSupported()) {
      return new NoopProbeProfiler("profiler_unsupported_platform");
    }
    try {
      return AsyncProfilerProbeProfiler.create(outputDirectory);
    } catch (RuntimeException ex) {
      return new NoopProbeProfiler("profiler_init_failed:" + sanitizeDetail(ex.getMessage()));
    }
  }

  private static String sanitizeDetail(String detail) {
    if (detail == null || detail.isBlank()) {
      return "unknown";
    }
    return detail.trim().replace('\n', ' ').replace('\r', ' ');
  }
}
