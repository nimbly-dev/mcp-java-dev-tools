package com.nimbly.mcpjavadevtools.agent.bootstrap;

import com.nimbly.mcpjavadevtools.agent.config.AgentConfig;
import com.nimbly.mcpjavadevtools.agent.debug.api.DebugApi;
import com.nimbly.mcpjavadevtools.agent.control.http.ProbeHttpServer;
import com.nimbly.mcpjavadevtools.agent.instrumentation.application.ProbeInstrumentation;
import com.nimbly.mcpjavadevtools.agent.profiler.api.ProfilerApi;
import com.nimbly.mcpjavadevtools.agent.runtime.api.CorrelationApi;
import com.nimbly.mcpjavadevtools.agent.runtime.api.RuntimeApi;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class ProbeLifecycle {
  private static final String BYTE_BUDDY_EXPERIMENTAL_PROPERTY = "net.bytebuddy.experimental";
  private static final Object LIFECYCLE_LOCK = new Object();
  private static final ProbeInstrumentation PROBE_INSTRUMENTATION = new ProbeInstrumentation();
  private static ProbeHttpServer activeHttpServer;
  private static Boolean bootstrapCorrelationReady;
  private static boolean active;

  private ProbeLifecycle() {
  }

  static void premain(String agentArgs, Instrumentation instrumentation) {
    LifecycleResult result = initialize(agentArgs, instrumentation);
    if (!result.success()) {
      System.err.println("[probe-agent] Startup initialization failed: " + result.reasonCode());
    }
  }

  static void agentmain(String agentArgs, Instrumentation instrumentation) {
    LifecycleCommand command = LifecycleCommand.parse(agentArgs);
    LifecycleResult result = command.deactivate()
        ? deactivate()
        : initialize(agentArgs, instrumentation);
    LifecycleReportWriter.write(command.reportFile(), result);
    if (!result.success()) {
      throw new IllegalStateException(result.reasonCode());
    }
  }

  private static LifecycleResult initialize(String agentArgs, Instrumentation instrumentation) {
    synchronized (LIFECYCLE_LOCK) {
      if (active) {
        return LifecycleResult.active("already_active");
      }
      return initializeInactiveAgent(agentArgs, instrumentation);
    }
  }

  private static LifecycleResult initializeInactiveAgent(
      String agentArgs,
      Instrumentation instrumentation
  ) {
    boolean jdkCorrelationReady = appendCorrelationContextOnce(instrumentation);
    AgentConfig config = AgentConfig.fromAgentArgs(agentArgs);
    configureRuntime(config);
    try {
      startProbeServer(config);
    } catch (IOException exception) {
      System.err.println("[probe-agent] Failed to start HTTP server: " + exception.getMessage());
      return LifecycleResult.failed("probe_server_start_failed");
    }

    try {
      PROBE_INSTRUMENTATION.install(instrumentation, config::shouldInstrument, jdkCorrelationReady);
      RuntimeApi.registerLoadedClassResolver(PROBE_INSTRUMENTATION::loadedClasses);
      RuntimeApi.registerApplicationClassResolver(config::shouldInstrument);
      active = true;
      return LifecycleResult.active("active");
    } catch (RuntimeException exception) {
      activeHttpServer.stop();
      activeHttpServer = null;
      System.err.println("[probe-agent] Failed to install instrumentation: " + exception.getMessage());
      return LifecycleResult.failed("instrumentation_installation_failed");
    }
  }

  private static void configureRuntime(AgentConfig config) {
    RuntimeApi.configure(
        config.mode,
        config.actuatorId,
        config.actuateTargetKey,
        config.actuateReturnBoolean,
        config.probeId
    );
    CorrelationApi.configureFromSystemProperties();
    DebugApi.configureCapture(
        config.captureEnabled,
        config.captureMaxKeys,
        config.captureMaxArgs,
        config.captureMethodBufferSize,
        config.capturePreviewMaxChars,
        config.captureStoredMaxChars,
        config.captureRedactionMode
    );
    DebugApi.configureExecutionPathScope(config.includePatterns, config.excludePatterns);
    ProfilerApi.configureDefault(ProfilerApi.resolveConfiguredOutputDirectory());
  }

  private static void startProbeServer(AgentConfig config) throws IOException {
    activeHttpServer = ProbeHttpServer.start(config.host, config.port);
    System.err.println("[probe-agent] HTTP listening on http://" + config.host + ":" + config.port);
    System.err.println("[probe-agent] status path: /__probe/status?key=...");
    System.err.println("[probe-agent] reset path:  /__probe/reset");
    System.err.println("[probe-agent] actuate path:/__probe/actuate");
    System.err.println("[probe-agent] capture path:/__probe/capture?captureId=...");
    System.err.println("[probe-agent] profiler path:/__probe/profiler");
    System.err.println(
        "[probe-agent] mode: observe (runtime-wide actuation retired; use session-scoped probe_enable)"
    );
    System.err.println("[probe-agent] captureEnabled: " + config.captureEnabled);
    System.err.println("[probe-agent] captureMaxKeys: " + config.captureMaxKeys);
    System.err.println("[probe-agent] captureMaxArgs: " + config.captureMaxArgs);
    System.err.println("[probe-agent] captureMethodBufferSize: " + config.captureMethodBufferSize);
    System.err.println("[probe-agent] capturePreviewMaxChars: " + config.capturePreviewMaxChars);
    System.err.println("[probe-agent] captureStoredMaxChars: " + config.captureStoredMaxChars);
    System.err.println("[probe-agent] captureRedactionMode: " + config.captureRedactionMode);
    System.err.println("[probe-agent] net.bytebuddy.experimental: "
        + System.getProperty(BYTE_BUDDY_EXPERIMENTAL_PROPERTY, "false"));
    logScope("include", config.includePatterns, config.includeSource);
    logScope("exclude", config.excludePatterns, config.excludeSource);
    if (config.includePatterns.isEmpty()) {
      System.err.println(
          "[probe-agent][warn] Include scope is empty. "
              + "No classes will be instrumented unless include is inferred or explicitly configured."
      );
    }
    List<String> broadIncludePatterns = config.broadIncludePatterns();
    if (!broadIncludePatterns.isEmpty()) {
      System.err.println(
          "[probe-agent][warn] Broad include patterns detected: "
              + String.join(",", broadIncludePatterns)
              + ". This may instrument far more classes than intended."
      );
    }
    if (activeHttpServer.rawServer() == null) {
      throw new IllegalStateException("HTTP server failed to initialize");
    }
  }

  private static void logScope(String name, List<String> patterns, String source) {
    System.err.println(
        "[probe-agent] " + name + ": "
            + (patterns.isEmpty() ? "(none)" : String.join(",", patterns))
            + " (source: " + source + ")"
    );
  }

  private static LifecycleResult deactivate() {
    synchronized (LIFECYCLE_LOCK) {
      if (!active) {
        return LifecycleResult.deactivated("already_inactive", List.of());
      }
      ProbeInstrumentation.DeactivationResult result = PROBE_INSTRUMENTATION.deactivate();
      if (!result.transformersRemoved()) {
        return LifecycleResult.failed("transformer_removal_failed");
      }
      stopProbeServer();
      RuntimeApi.registerLoadedClassResolver(null);
      RuntimeApi.registerApplicationClassResolver(null);
      active = false;
      if (result.nonRestorableClasses().isEmpty()) {
        return LifecycleResult.deactivated("deactivated", List.of());
      }
      return LifecycleResult.deactivated("non_restorable_classes", result.nonRestorableClasses());
    }
  }

  private static void stopProbeServer() {
    if (activeHttpServer != null) {
      activeHttpServer.stop();
      activeHttpServer = null;
    }
  }

  private static boolean appendCorrelationContextOnce(Instrumentation instrumentation) {
    if (bootstrapCorrelationReady == null) {
      bootstrapCorrelationReady = appendCorrelationContextToBootstrap(instrumentation);
    }
    return bootstrapCorrelationReady;
  }

  private static boolean appendCorrelationContextToBootstrap(Instrumentation instrumentation) {
    String[] bootstrapClasses = {
      "com/nimbly/mcpjavadevtools/agent/runtime/CorrelationContext.class",
      "com/nimbly/mcpjavadevtools/agent/runtime/CorrelationContext$Binding.class",
      "com/nimbly/mcpjavadevtools/agent/runtime/CorrelationContext$BindingSnapshot.class",
      "com/nimbly/mcpjavadevtools/agent/runtime/api/BootstrapCorrelationApi.class"
    };
    try {
      Path bootstrapJar = Files.createTempFile("mcp-correlation-bootstrap-", ".jar");
      try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(bootstrapJar))) {
        for (String classResource : bootstrapClasses) {
          try (InputStream input = ProbeLifecycle.class.getClassLoader().getResourceAsStream(classResource)) {
            if (input == null) {
              return false;
            }
            output.putNextEntry(new JarEntry(classResource));
            input.transferTo(output);
            output.closeEntry();
          }
        }
      }
      instrumentation.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(bootstrapJar.toFile()));
      return true;
    } catch (Exception exception) {
      System.err.println("[probe-agent] JDK correlation handoff instrumentation disabled: "
          + exception.getMessage());
      return false;
    }
  }

  record LifecycleResult(
      boolean success,
      String outcome,
      String reasonCode,
      List<String> nonRestorableClasses
  ) {
    static LifecycleResult active(String reasonCode) {
      return new LifecycleResult(true, "active", reasonCode, List.of());
    }

    static LifecycleResult deactivated(String reasonCode, List<String> nonRestorableClasses) {
      String outcome = nonRestorableClasses.isEmpty() ? "deactivated" : "partial";
      return new LifecycleResult(true, outcome, reasonCode, List.copyOf(nonRestorableClasses));
    }

    static LifecycleResult failed(String reasonCode) {
      return new LifecycleResult(false, "failed", reasonCode, List.of());
    }
  }
}
