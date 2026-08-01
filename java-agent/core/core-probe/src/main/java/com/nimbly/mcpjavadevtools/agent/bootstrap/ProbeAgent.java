package com.nimbly.mcpjavadevtools.agent.bootstrap;

import com.nimbly.mcpjavadevtools.agent.capture.ProbeCaptureStore;
import com.nimbly.mcpjavadevtools.agent.config.AgentConfig;
import com.nimbly.mcpjavadevtools.agent.control.http.ProbeHttpServer;
import com.nimbly.mcpjavadevtools.agent.instrumentation.HitAdvice;
import com.nimbly.mcpjavadevtools.agent.instrumentation.LineHitVisitor;
import com.nimbly.mcpjavadevtools.agent.profiler.ProbeProfilerRegistry;
import com.nimbly.mcpjavadevtools.agent.profiler.ProfilerPaths;
import com.nimbly.mcpjavadevtools.agent.runtime.ProbeRuntime;
import com.nimbly.mcpjavadevtools.agent.runtime.CorrelationConsumerAdvice;
import com.nimbly.mcpjavadevtools.agent.runtime.CorrelationBoundaryInstaller;
import com.nimbly.mcpjavadevtools.agent.runtime.CorrelationConsumerBoundary;
import com.nimbly.mcpjavadevtools.agent.runtime.CorrelationEventConsumerAdapter;
import com.nimbly.mcpjavadevtools.agent.runtime.JdkExecutorCorrelationAdvice;
import com.nimbly.mcpjavadevtools.agent.runtime.KclConsumerAdvice;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class ProbeAgent {
  private static final String BYTE_BUDDY_EXPERIMENTAL_PROPERTY = "net.bytebuddy.experimental";
  private static final Object LIFECYCLE_LOCK = new Object();
  private static final List<ResettableClassFileTransformer> ACTIVE_TRANSFORMERS = new ArrayList<>();
  private static final Set<String> TRANSFORMED_CLASS_NAMES = ConcurrentHashMap.newKeySet();
  private static ProbeHttpServer activeHttpServer;
  private static Instrumentation activeInstrumentation;
  private static Boolean bootstrapCorrelationReady;
  private static boolean active;

  private ProbeAgent() {}

  public static void premain(String agentArgs, Instrumentation inst) {
    LifecycleResult result = initialize(agentArgs, inst);
    if (!result.success()) {
      System.err.println("[probe-agent] Startup initialization failed: " + result.reasonCode());
    }
  }

  public static void agentmain(String agentArgs, Instrumentation inst) {
    LifecycleCommand command = LifecycleCommand.parse(agentArgs);
    LifecycleResult result = command.deactivate()
        ? deactivate()
        : initialize(agentArgs, inst);
    LifecycleReportWriter.write(command.reportFile(), result);
    if (!result.success()) {
      throw new IllegalStateException(result.reasonCode());
    }
  }

  private static LifecycleResult initialize(String agentArgs, Instrumentation inst) {
    synchronized (LIFECYCLE_LOCK) {
      if (active) {
        return LifecycleResult.active("already_active");
      }
      return initializeInactiveAgent(agentArgs, inst);
    }
  }

  private static LifecycleResult initializeInactiveAgent(String agentArgs, Instrumentation inst) {
    boolean jdkCorrelationReady = appendCorrelationContextOnce(inst);
    AgentConfig cfg = AgentConfig.fromAgentArgs(agentArgs);
    ProbeRuntime.configure(
        cfg.mode,
        cfg.actuatorId,
        cfg.actuateTargetKey,
        cfg.actuateReturnBoolean,
        cfg.probeId
    );
    CorrelationEventConsumerAdapter.configureFromSystemProperties();
    ProbeCaptureStore.configureCapture(
        cfg.captureEnabled,
        cfg.captureMaxKeys,
        cfg.captureMaxArgs,
        cfg.captureMethodBufferSize,
        cfg.capturePreviewMaxChars,
        cfg.captureStoredMaxChars,
        cfg.captureRedactionMode
    );
    ProbeCaptureStore.configureExecutionPathScope(cfg.includePatterns, cfg.excludePatterns);
    ProbeProfilerRegistry.configureDefault(ProfilerPaths.resolveConfiguredOutputDirectory());

    try {
      activeHttpServer = ProbeHttpServer.start(cfg.host, cfg.port);
      System.err.println("[probe-agent] HTTP listening on http://" + cfg.host + ":" + cfg.port);
      System.err.println("[probe-agent] status path: /__probe/status?key=...");
      System.err.println("[probe-agent] reset path:  /__probe/reset");
      System.err.println("[probe-agent] actuate path:/__probe/actuate");
      System.err.println("[probe-agent] capture path:/__probe/capture?captureId=...");
      System.err.println("[probe-agent] profiler path:/__probe/profiler");
      System.err.println("[probe-agent] mode: observe (runtime-wide actuation retired; use session-scoped probe_enable)");
      System.err.println("[probe-agent] captureEnabled: " + cfg.captureEnabled);
      System.err.println("[probe-agent] captureMaxKeys: " + cfg.captureMaxKeys);
      System.err.println("[probe-agent] captureMaxArgs: " + cfg.captureMaxArgs);
      System.err.println("[probe-agent] captureMethodBufferSize: " + cfg.captureMethodBufferSize);
      System.err.println("[probe-agent] capturePreviewMaxChars: " + cfg.capturePreviewMaxChars);
      System.err.println("[probe-agent] captureStoredMaxChars: " + cfg.captureStoredMaxChars);
      System.err.println("[probe-agent] captureRedactionMode: " + cfg.captureRedactionMode);
      System.err.println("[probe-agent] net.bytebuddy.experimental: "
          + System.getProperty(BYTE_BUDDY_EXPERIMENTAL_PROPERTY, "false"));
      System.err.println(
          "[probe-agent] include: "
              + (cfg.includePatterns.isEmpty() ? "(none)" : String.join(",", cfg.includePatterns))
              + " (source: "
              + cfg.includeSource
              + ")"
      );
      System.err.println(
          "[probe-agent] exclude: "
              + (cfg.excludePatterns.isEmpty() ? "(none)" : String.join(",", cfg.excludePatterns))
              + " (source: "
              + cfg.excludeSource
              + ")"
      );
      if (cfg.includePatterns.isEmpty()) {
        System.err.println(
            "[probe-agent][warn] Include scope is empty. "
                + "No classes will be instrumented unless include is inferred or explicitly configured."
        );
      }
      List<String> broadIncludePatterns = cfg.broadIncludePatterns();
      if (!broadIncludePatterns.isEmpty()) {
        System.err.println(
            "[probe-agent][warn] Broad include patterns detected: "
                + String.join(",", broadIncludePatterns)
                + ". This may instrument far more classes than intended."
        );
      }
      // keep reference so GC doesn't collect server
      if (activeHttpServer.rawServer() == null) {
        throw new IllegalStateException("HTTP server failed to initialize");
      }
    } catch (IOException e) {
      System.err.println("[probe-agent] Failed to start HTTP server: " + e.getMessage());
      return LifecycleResult.failed("probe_server_start_failed");
    }

    try {
      installInstrumentation(inst, cfg, jdkCorrelationReady);
      activeInstrumentation = inst;
      ProbeRuntime.registerLoadedClassResolver(className -> loadedClasses(inst, className));
      ProbeRuntime.registerApplicationClassResolver(cfg::shouldInstrument);
      active = true;
      return LifecycleResult.active("active");
    } catch (RuntimeException exception) {
      activeHttpServer.stop();
      activeHttpServer = null;
      System.err.println("[probe-agent] Failed to install instrumentation: " + exception.getMessage());
      return LifecycleResult.failed("instrumentation_installation_failed");
    }
  }

  private static void installInstrumentation(
      Instrumentation inst, AgentConfig cfg, boolean jdkCorrelationReady) {
    AgentBuilder builder = new AgentBuilder.Default()
        .disableClassFormatChanges()
        .ignore(ElementMatchers.nameStartsWith("net.bytebuddy.")
            .or(ElementMatchers.nameStartsWith("java."))
            .or(ElementMatchers.nameStartsWith("javax."))
            .or(ElementMatchers.nameStartsWith("jakarta."))
            .or(ElementMatchers.nameStartsWith("sun."))
            .or(ElementMatchers.nameStartsWith("jdk."))
            .or(ElementMatchers.nameStartsWith("com.sun."))
            .or(ElementMatchers.nameStartsWith("org.springframework.boot.loader.")));

    if (inst.isRetransformClassesSupported()) {
      builder = builder.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
    }

    ResettableClassFileTransformer applicationTransformer = builder
        .type(new ElementMatcher<TypeDescription>() {
          @Override
          public boolean matches(TypeDescription td) {
            return cfg.shouldInstrument(td.getName());
          }
        })
        .transform(new AgentBuilder.Transformer() {
          @Override
          public DynamicType.Builder<?> transform(
              DynamicType.Builder<?> b,
              TypeDescription td,
              ClassLoader cl,
              JavaModule module,
              ProtectionDomain pd
          ) {
            ElementMatcher.Junction<MethodDescription> matcher =
                ElementMatchers.isMethod()
                    .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                    .and(ElementMatchers.not(ElementMatchers.isNative()))
                    .and(ElementMatchers.not(ElementMatchers.nameStartsWith("lambda$")));
            DynamicType.Builder<?> out = b.visit(Advice.to(HitAdvice.class).on(matcher));
            ElementMatcher.Junction<MethodDescription> consumerMatcher = buildConsumerMatcher();
            out = out.visit(Advice.to(CorrelationConsumerAdvice.class).on(consumerMatcher));
            ElementMatcher.Junction<MethodDescription> kclConsumerMatcher =
                ElementMatchers.named("processRecords")
                    .and(ElementMatchers.takesArguments(1))
                    .and(ElementMatchers.takesArgument(
                        0,
                        ElementMatchers.named(
                            "software.amazon.kinesis.lifecycle.events.ProcessRecordsInput")))
                    .and(ElementMatchers.returns(void.class))
                    .and(ElementMatchers.isDeclaredBy(
                        ElementMatchers.hasSuperType(
                            ElementMatchers.named("software.amazon.kinesis.processor.ShardRecordProcessor"))));
            out = out.visit(Advice.to(KclConsumerAdvice.class).on(kclConsumerMatcher));
            out = out.visit(new LineHitVisitor(td.getName()));
            return out;
          }
        })
        .with(new AgentBuilder.Listener() {
          @Override
          public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
          }

          @Override
          public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded, DynamicType dynamicType) {
            TRANSFORMED_CLASS_NAMES.add(typeDescription.getName());
            System.err.println("[probe-agent] Instrumented: " + typeDescription.getName());
          }

          @Override
          public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded) {
          }

          @Override
          public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
            System.err.println("[probe-agent] Transform error: " + typeName + " -> " + throwable);
          }

          @Override
          public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
          }
        })
        .installOn(inst);
    ACTIVE_TRANSFORMERS.add(applicationTransformer);
    retransformConfiguredClasses(inst, cfg);
    registerCorrelationBoundaryInstaller(inst, cfg);

    if (!jdkCorrelationReady) {
      return;
    }
    AgentBuilder jdkBuilder = new AgentBuilder.Default()
        .disableClassFormatChanges()
        .ignore(ElementMatchers.none());
    if (inst.isRetransformClassesSupported()) {
      jdkBuilder = jdkBuilder.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
    }
    ResettableClassFileTransformer jdkTransformer = jdkBuilder
        .type(ElementMatchers.named("java.util.concurrent.AbstractExecutorService")
            .or(ElementMatchers.named("java.util.concurrent.ThreadPoolExecutor"))
            .or(ElementMatchers.named("java.util.concurrent.ScheduledThreadPoolExecutor"))
            .or(ElementMatchers.named("java.util.concurrent.ForkJoinPool")))
        .transform((b, td, cl, module, pd) -> b.visit(Advice.to(JdkExecutorCorrelationAdvice.class).on(
            ElementMatchers.named("execute")
                .or(ElementMatchers.named("submit"))
                .or(ElementMatchers.named("schedule"))
                .or(ElementMatchers.named("scheduleAtFixedRate"))
                .or(ElementMatchers.named("scheduleWithFixedDelay")))))
        .installOn(inst);
    ACTIVE_TRANSFORMERS.add(jdkTransformer);
    retransformJdkExecutors(inst);
  }

  private static LifecycleResult deactivate() {
    synchronized (LIFECYCLE_LOCK) {
      if (!active) {
        return LifecycleResult.deactivated("already_inactive", List.of());
      }
      boolean transformersRemoved = removeOwnedTransformers();
      List<String> nonRestorableClasses = restoreTransformedClasses();
      if (!transformersRemoved) {
        return LifecycleResult.failed("transformer_removal_failed");
      }
      stopProbeServer();
      ProbeRuntime.registerCorrelationBoundaryInstaller(null);
      ProbeRuntime.registerLoadedClassResolver(null);
      ProbeRuntime.registerApplicationClassResolver(null);
      ACTIVE_TRANSFORMERS.clear();
      TRANSFORMED_CLASS_NAMES.clear();
      activeInstrumentation = null;
      active = false;
      if (nonRestorableClasses.isEmpty()) {
        return LifecycleResult.deactivated("deactivated", List.of());
      }
      return LifecycleResult.deactivated("non_restorable_classes", nonRestorableClasses);
    }
  }

  private static void retransformConfiguredClasses(Instrumentation inst, AgentConfig cfg) {
    if (!inst.isRetransformClassesSupported()) {
      return;
    }
    for (Class<?> loadedClass : inst.getAllLoadedClasses()) {
      if (!cfg.shouldInstrument(loadedClass.getName()) || !inst.isModifiableClass(loadedClass)) {
        continue;
      }
      try {
        inst.retransformClasses(loadedClass);
      } catch (Exception exception) {
        System.err.println("[probe-agent] Failed to retransform " + loadedClass.getName()
            + ": " + exception.getMessage());
      }
    }
  }

  private static List<Class<?>> loadedClasses(Instrumentation instrumentation, String className) {
    List<Class<?>> matches = new ArrayList<>();
    for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
      if (className.equals(loadedClass.getName())) matches.add(loadedClass);
    }
    return List.copyOf(matches);
  }

  private static boolean removeOwnedTransformers() {
    boolean removed = true;
    for (ResettableClassFileTransformer transformer : ACTIVE_TRANSFORMERS) {
      if (!activeInstrumentation.removeTransformer(transformer)) {
        removed = false;
      }
    }
    return removed;
  }

  private static List<String> restoreTransformedClasses() {
    List<String> nonRestorable = new ArrayList<>();
    for (Class<?> loadedClass : activeInstrumentation.getAllLoadedClasses()) {
      if (!TRANSFORMED_CLASS_NAMES.contains(loadedClass.getName())) {
        continue;
      }
      if (!activeInstrumentation.isModifiableClass(loadedClass)
          || !activeInstrumentation.isRetransformClassesSupported()) {
        nonRestorable.add(loadedClass.getName());
        continue;
      }
      try {
        activeInstrumentation.retransformClasses(loadedClass);
      } catch (Exception exception) {
        nonRestorable.add(loadedClass.getName());
      }
    }
    return List.copyOf(nonRestorable);
  }

  private static void stopProbeServer() {
    if (activeHttpServer != null) {
      activeHttpServer.stop();
      activeHttpServer = null;
    }
  }

  private static ElementMatcher.Junction<MethodDescription> buildConsumerMatcher() {
    ElementMatcher.Junction<MethodDescription> matcher =
        ElementMatchers.isAnnotatedWith(ElementMatchers.named("org.springframework.context.event.EventListener"))
            .or(ElementMatchers.isAnnotatedWith(ElementMatchers.named("org.springframework.kafka.annotation.KafkaListener")))
            .or(ElementMatchers.isAnnotatedWith(ElementMatchers.named("org.springframework.amqp.rabbit.annotation.RabbitListener")))
            .or(ElementMatchers.isAnnotatedWith(ElementMatchers.named("org.springframework.jms.annotation.JmsListener")))
            // Deprecated compatibility path. Keep convention consumers instrumented while
            // plans migrate to exact contract-owned correlation.consumerBoundaries selectors.
            .or(ElementMatchers.nameMatches("(?i)(receive|consume|onMessage|handleMessage)[A-Z_].*"));
    for (var boundary : CorrelationEventConsumerAdapter.configuredConsumerBoundaries()) {
      matcher = matcher.or(buildBoundaryMatcher(boundary));
    }
    return matcher;
  }

  private static ElementMatcher.Junction<MethodDescription> buildBoundaryMatcher(
      CorrelationConsumerBoundary boundary) {
    ElementMatcher.Junction<MethodDescription> matcher = ElementMatchers.isMethod()
        .and(ElementMatchers.not(ElementMatchers.isAbstract()))
        .and(ElementMatchers.not(ElementMatchers.isNative()))
        .and(ElementMatchers.isDeclaredBy(ElementMatchers.named(boundary.fqcn())))
        .and(ElementMatchers.named(boundary.method()))
        .and(ElementMatchers.takesArguments(boundary.parameterTypes().size()));
    for (int index = 0; index < boundary.parameterTypes().size(); index++) {
      matcher = matcher.and(ElementMatchers.takesArgument(
          index,
          ElementMatchers.named(boundary.parameterTypes().get(index))));
    }
    return matcher;
  }

  private static void registerCorrelationBoundaryInstaller(
      Instrumentation inst,
      AgentConfig cfg) {
    Set<String> configuredClassNames = ConcurrentHashMap.newKeySet();
    ProbeRuntime.registerCorrelationBoundaryInstaller(boundaries -> {
      configuredClassNames.clear();
      for (var boundary : boundaries) {
        if (!cfg.shouldInstrument(boundary.fqcn())) {
          return CorrelationBoundaryInstaller.InstallationResult.failed(
              "correlation_boundary_outside_instrumentation_scope");
        }
        configuredClassNames.add(boundary.fqcn());
      }
      if (!inst.isRetransformClassesSupported()) {
        return CorrelationBoundaryInstaller.InstallationResult.failed(
            "correlation_boundary_retransformation_unsupported");
      }
      for (Class<?> loadedType : inst.getAllLoadedClasses()) {
        if (!configuredClassNames.contains(loadedType.getName())) {
          continue;
        }
        if (!inst.isModifiableClass(loadedType)) {
          return CorrelationBoundaryInstaller.InstallationResult.failed(
              "correlation_boundary_class_not_modifiable");
        }
        try {
          inst.retransformClasses(loadedType);
        } catch (Exception exception) {
          return CorrelationBoundaryInstaller.InstallationResult.failed(
              "correlation_boundary_installation_failed");
        }
      }
      return CorrelationBoundaryInstaller.InstallationResult.success();
    });
  }

  private static void retransformJdkExecutors(Instrumentation inst) {
    if (!inst.isRetransformClassesSupported()) {
      return;
    }
    for (Class<?> loadedType : inst.getAllLoadedClasses()) {
      if (!loadedType.getName().equals("java.util.concurrent.AbstractExecutorService")
          && !loadedType.getName().equals("java.util.concurrent.ThreadPoolExecutor")
          && !loadedType.getName().equals("java.util.concurrent.ScheduledThreadPoolExecutor")
          && !loadedType.getName().equals("java.util.concurrent.ForkJoinPool")) {
        continue;
      }
      if (!inst.isModifiableClass(loadedType)) {
        continue;
      }
      try {
        inst.retransformClasses(loadedType);
        TRANSFORMED_CLASS_NAMES.add(loadedType.getName());
      } catch (Exception exception) {
        System.err.println("[probe-agent] Failed to retransform JDK executor "
            + loadedType.getName() + ": " + exception.getMessage());
      }
    }
  }

  private static boolean appendCorrelationContextOnce(Instrumentation inst) {
    if (bootstrapCorrelationReady == null) {
      bootstrapCorrelationReady = appendCorrelationContextToBootstrap(inst);
    }
    return bootstrapCorrelationReady;
  }

  private static boolean appendCorrelationContextToBootstrap(Instrumentation inst) {
    String[] bootstrapClasses = {
      "com/nimbly/mcpjavadevtools/agent/runtime/CorrelationContext.class",
      "com/nimbly/mcpjavadevtools/agent/runtime/CorrelationContext$Binding.class",
      "com/nimbly/mcpjavadevtools/agent/runtime/CorrelationContext$BindingSnapshot.class"
    };
    try {
      Path bootstrapJar = Files.createTempFile("mcp-correlation-bootstrap-", ".jar");
      try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(bootstrapJar))) {
        for (String classResource : bootstrapClasses) {
          try (InputStream input = ProbeAgent.class.getClassLoader().getResourceAsStream(classResource)) {
            if (input == null) {
              return false;
            }
            output.putNextEntry(new JarEntry(classResource));
            input.transferTo(output);
            output.closeEntry();
          }
        }
      }
      inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(bootstrapJar.toFile()));
      return true;
    } catch (Exception exception) {
      System.err.println("[probe-agent] JDK correlation handoff instrumentation disabled: "
          + exception.getMessage());
      return false;
    }
  }

  record LifecycleResult(boolean success, String outcome, String reasonCode,
                         List<String> nonRestorableClasses) {
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

