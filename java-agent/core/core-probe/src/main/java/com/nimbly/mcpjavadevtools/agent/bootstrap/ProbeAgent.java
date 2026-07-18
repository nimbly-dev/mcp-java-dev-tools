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
import com.nimbly.mcpjavadevtools.agent.runtime.CorrelationEventConsumerAdapter;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public final class ProbeAgent {
  private static final String BYTE_BUDDY_EXPERIMENTAL_PROPERTY = "net.bytebuddy.experimental";

  private ProbeAgent() {}

  public static void premain(String agentArgs, Instrumentation inst) {
    AgentConfig cfg = AgentConfig.fromAgentArgs(agentArgs);
    configureByteBuddyCompatibility(cfg);
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
      ProbeHttpServer http = ProbeHttpServer.start(cfg.host, cfg.port);
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
      System.err.println("[probe-agent] byteBuddyExperimentalCompatibility: " + cfg.byteBuddyExperimentalCompatibility);
      System.err.println("[probe-agent] net.bytebuddy.experimental: " + System.getProperty(BYTE_BUDDY_EXPERIMENTAL_PROPERTY, "false"));
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
      java.util.List<String> broadIncludePatterns = cfg.broadIncludePatterns();
      if (!broadIncludePatterns.isEmpty()) {
        System.err.println(
            "[probe-agent][warn] Broad include patterns detected: "
                + String.join(",", broadIncludePatterns)
                + ". This may instrument far more classes than intended."
        );
      }
      // keep reference so GC doesn't collect server
      if (http.rawServer() == null) {
        throw new IllegalStateException("HTTP server failed to initialize");
      }
    } catch (IOException e) {
      System.err.println("[probe-agent] Failed to start HTTP server: " + e.getMessage());
    }

    installInstrumentation(inst, cfg);
  }

  private static void configureByteBuddyCompatibility(AgentConfig cfg) {
    if (!cfg.byteBuddyExperimentalCompatibility) {
      return;
    }
    System.setProperty(BYTE_BUDDY_EXPERIMENTAL_PROPERTY, "true");
  }

  private static void installInstrumentation(Instrumentation inst, AgentConfig cfg) {
    AgentBuilder builder = new AgentBuilder.Default()
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

    builder
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
            ElementMatcher.Junction<MethodDescription> consumerMatcher =
                ElementMatchers.isAnnotatedWith(ElementMatchers.named("org.springframework.context.event.EventListener"))
                    .or(ElementMatchers.isAnnotatedWith(ElementMatchers.named("org.springframework.kafka.annotation.KafkaListener")))
                    .or(ElementMatchers.isAnnotatedWith(ElementMatchers.named("org.springframework.amqp.rabbit.annotation.RabbitListener")))
                    .or(ElementMatchers.isAnnotatedWith(ElementMatchers.named("org.springframework.jms.annotation.JmsListener")))
                    .or(ElementMatchers.nameMatches("(?i)(receive|consume|onMessage|handleMessage)[A-Z_].*"));
            out = out.visit(Advice.to(CorrelationConsumerAdvice.class).on(consumerMatcher));
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
  }
}

