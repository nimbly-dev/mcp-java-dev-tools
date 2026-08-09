package com.nimbly.mcpjavadevtools.agent.profiler;

import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStartRequest;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStateSnapshot;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

public final class ProbeProfilerRegistry {
  private static final String PROVIDER_AUTO = "auto";
  private static final String PROVIDER_ASYNC = "async-profiler";
  private static final String PROVIDER_JFR = "jfr";
  private static final AtomicReference<ProviderSet> PROVIDERS =
      new AtomicReference<>(ProviderSet.uninitialized());
  private static final AtomicReference<ProbeProfiler> ACTIVE =
      new AtomicReference<>(new NoopProbeProfiler("profiler_uninitialized"));

  private ProbeProfilerRegistry() {}

  public static void configureDefault(Path outputDirectory) {
    ProviderSet providers = new ProviderSet(
        createAsyncProfiler(outputDirectory),
        createJfrProfiler(outputDirectory)
    );
    PROVIDERS.set(providers);
    ACTIVE.set(selectProvider(PROVIDER_AUTO, providers));
  }

  public static ProbeProfiler active() {
    return ACTIVE.get();
  }

  public static ProfilerStateSnapshot start(ProfilerStartRequest request) {
    ProbeProfiler current = ACTIVE.get();
    if ("running".equals(current.state().status())) {
      return current.start(request);
    }
    ProviderSet providers = PROVIDERS.get();
    ProbeProfiler selected = selectProvider(providerIntent(request), providers);
    ACTIVE.set(selected);
    return selected.start(request);
  }

  private static String providerIntent(ProfilerStartRequest request) {
    if (request == null || request.provider() == null || request.provider().isBlank()) {
      return PROVIDER_AUTO;
    }
    return request.provider().trim().toLowerCase();
  }

  private static ProbeProfiler selectProvider(String requested, ProviderSet providers) {
    if (PROVIDER_AUTO.equals(requested)) {
      if (providers.asyncProfiler().state().supported()) return providers.asyncProfiler();
      if (providers.jfrProfiler().state().supported()) return providers.jfrProfiler();
      return new NoopProbeProfiler(PROVIDER_AUTO, "profiler_no_provider_available");
    }
    if (PROVIDER_ASYNC.equals(requested)) return providers.asyncProfiler();
    if (PROVIDER_JFR.equals(requested)) return providers.jfrProfiler();
    return new NoopProbeProfiler(requested, "profiler_provider_invalid:" + requested);
  }

  private static ProbeProfiler createAsyncProfiler(Path outputDirectory) {
    if (!AsyncProfilerProbeProfiler.isSupported()) {
      return new NoopProbeProfiler(
          PROVIDER_ASYNC,
          "profiler_unsupported_platform"
      );
    }
    try {
      return AsyncProfilerProbeProfiler.create(outputDirectory);
    } catch (RuntimeException ex) {
      return new NoopProbeProfiler(
          PROVIDER_ASYNC,
          "profiler_init_failed:" + sanitizeDetail(ex.getMessage())
      );
    }
  }

  private static ProbeProfiler createJfrProfiler(Path outputDirectory) {
    if (!JfrProbeProfiler.isSupported()) {
      return new NoopProbeProfiler(PROVIDER_JFR, "profiler_provider_unavailable:jfr");
    }
    try {
      return JfrProbeProfiler.create(outputDirectory);
    } catch (RuntimeException ex) {
      return new NoopProbeProfiler(
          PROVIDER_JFR,
          "profiler_init_failed:" + sanitizeDetail(ex.getMessage())
      );
    }
  }

  private static String sanitizeDetail(String detail) {
    if (detail == null || detail.isBlank()) return "unknown";
    return detail.trim().replace('\n', ' ').replace('\r', ' ');
  }

  private record ProviderSet(ProbeProfiler asyncProfiler, ProbeProfiler jfrProfiler) {
    private static ProviderSet uninitialized() {
      return new ProviderSet(
          new NoopProbeProfiler(PROVIDER_ASYNC, "profiler_uninitialized"),
          new NoopProbeProfiler(PROVIDER_JFR, "profiler_uninitialized")
      );
    }
  }
}
