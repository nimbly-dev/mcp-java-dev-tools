package com.nimbly.mcpjavadevtools.agent.instrumentation.application;

import com.nimbly.mcpjavadevtools.agent.instrumentation.adapter.bytebuddy.CorrelationConsumerAdvice;
import com.nimbly.mcpjavadevtools.agent.instrumentation.adapter.bytebuddy.HitAdvice;
import com.nimbly.mcpjavadevtools.agent.instrumentation.adapter.bytebuddy.JdkExecutorCorrelationAdvice;
import com.nimbly.mcpjavadevtools.agent.instrumentation.adapter.bytebuddy.KclConsumerAdvice;
import com.nimbly.mcpjavadevtools.agent.instrumentation.adapter.bytebuddy.LineHitVisitor;

import com.nimbly.mcpjavadevtools.agent.runtime.api.CorrelationApi;
import com.nimbly.mcpjavadevtools.agent.runtime.api.RuntimeApi;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

/** Owns Byte Buddy transformer installation and restoration for the Probe. */
public final class ProbeInstrumentation {
  private final List<ResettableClassFileTransformer> activeTransformers = new ArrayList<>();
  private final Set<String> transformedClassNames = ConcurrentHashMap.newKeySet();
  private Instrumentation activeInstrumentation;

  public void install(
      Instrumentation instrumentation,
      Predicate<String> shouldInstrument,
      boolean jdkCorrelationReady
  ) {
    activeInstrumentation = instrumentation;
    AgentBuilder applicationBuilder = createApplicationBuilder(instrumentation);
    ResettableClassFileTransformer applicationTransformer = installApplicationTransformer(
        applicationBuilder,
        shouldInstrument
    );
    activeTransformers.add(applicationTransformer);
    retransformConfiguredClasses(instrumentation, shouldInstrument);
    registerCorrelationBoundaryInstaller(instrumentation, shouldInstrument);
    if (jdkCorrelationReady) {
      installJdkCorrelationTransformer(instrumentation);
    }
  }

  public List<Class<?>> loadedClasses(String className) {
    List<Class<?>> matches = new ArrayList<>();
    for (Class<?> loadedClass : activeInstrumentation.getAllLoadedClasses()) {
      if (className.equals(loadedClass.getName())) {
        matches.add(loadedClass);
      }
    }
    return List.copyOf(matches);
  }

  public DeactivationResult deactivate() {
    boolean transformersRemoved = removeOwnedTransformers();
    List<String> nonRestorableClasses = restoreTransformedClasses();
    if (transformersRemoved) {
      RuntimeApi.registerCorrelationBoundaryInstaller(null);
      activeTransformers.clear();
      transformedClassNames.clear();
      activeInstrumentation = null;
    }
    return new DeactivationResult(transformersRemoved, nonRestorableClasses);
  }

  private AgentBuilder createApplicationBuilder(Instrumentation instrumentation) {
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
    if (instrumentation.isRetransformClassesSupported()) {
      return builder.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
    }
    return builder;
  }

  private ResettableClassFileTransformer installApplicationTransformer(
      AgentBuilder builder,
      Predicate<String> shouldInstrument
  ) {
    return builder
        .type(new ElementMatcher<TypeDescription>() {
          @Override
          public boolean matches(TypeDescription typeDescription) {
            return shouldInstrument.test(typeDescription.getName());
          }
        })
        .transform(new AgentBuilder.Transformer() {
          @Override
          public DynamicType.Builder<?> transform(
              DynamicType.Builder<?> builder,
              TypeDescription typeDescription,
              ClassLoader classLoader,
              JavaModule module,
              ProtectionDomain protectionDomain
          ) {
            ElementMatcher.Junction<MethodDescription> methodMatcher =
                ElementMatchers.isMethod()
                    .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                    .and(ElementMatchers.not(ElementMatchers.isNative()))
                    .and(ElementMatchers.not(ElementMatchers.nameStartsWith("lambda$")));
            DynamicType.Builder<?> output = builder.visit(Advice.to(HitAdvice.class).on(methodMatcher));
            ElementMatcher.Junction<MethodDescription> consumerMatcher = buildConsumerMatcher();
            output = output.visit(Advice.to(CorrelationConsumerAdvice.class).on(consumerMatcher));
            output = output.visit(Advice.to(KclConsumerAdvice.class).on(buildKclConsumerMatcher()));
            return output.visit(new LineHitVisitor(typeDescription.getName()));
          }
        })
        .with(new AgentBuilder.Listener() {
          @Override
          public void onDiscovery(
              String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
          }

          @Override
          public void onTransformation(
              TypeDescription typeDescription,
              ClassLoader classLoader,
              JavaModule module,
              boolean loaded,
              DynamicType dynamicType
          ) {
            transformedClassNames.add(typeDescription.getName());
            System.err.println("[probe-agent] Instrumented: " + typeDescription.getName());
          }

          @Override
          public void onIgnored(
              TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded) {
          }

          @Override
          public void onError(
              String typeName,
              ClassLoader classLoader,
              JavaModule module,
              boolean loaded,
              Throwable throwable
          ) {
            System.err.println("[probe-agent] Transform error: " + typeName + " -> " + throwable);
          }

          @Override
          public void onComplete(
              String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
          }
        })
        .installOn(activeInstrumentation);
  }

  private ElementMatcher.Junction<MethodDescription> buildKclConsumerMatcher() {
    return ElementMatchers.named("processRecords")
        .and(ElementMatchers.takesArguments(1))
        .and(ElementMatchers.takesArgument(
            0,
            ElementMatchers.named(
                "software.amazon.kinesis.lifecycle.events.ProcessRecordsInput")))
        .and(ElementMatchers.returns(void.class))
        .and(ElementMatchers.isDeclaredBy(
            ElementMatchers.hasSuperType(
                ElementMatchers.named("software.amazon.kinesis.processor.ShardRecordProcessor"))));
  }

  private ElementMatcher.Junction<MethodDescription> buildConsumerMatcher() {
    ElementMatcher.Junction<MethodDescription> matcher =
        ElementMatchers.isAnnotatedWith(ElementMatchers.named("org.springframework.context.event.EventListener"))
            .or(ElementMatchers.isAnnotatedWith(ElementMatchers.named("org.springframework.kafka.annotation.KafkaListener")))
            .or(ElementMatchers.isAnnotatedWith(ElementMatchers.named("org.springframework.amqp.rabbit.annotation.RabbitListener")))
            .or(ElementMatchers.isAnnotatedWith(ElementMatchers.named("org.springframework.jms.annotation.JmsListener")))
            .or(ElementMatchers.nameMatches("(?i)(receive|consume|onMessage|handleMessage)[A-Z_].*"));
    for (RuntimeApi.CorrelationConsumerBoundary boundary : CorrelationApi.configuredConsumerBoundaries()) {
      matcher = matcher.or(buildBoundaryMatcher(boundary));
    }
    return matcher;
  }

  private ElementMatcher.Junction<MethodDescription> buildBoundaryMatcher(
      RuntimeApi.CorrelationConsumerBoundary boundary) {
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

  private void registerCorrelationBoundaryInstaller(
      Instrumentation instrumentation,
      Predicate<String> shouldInstrument
  ) {
    Set<String> configuredClassNames = ConcurrentHashMap.newKeySet();
    RuntimeApi.registerCorrelationBoundaryInstaller(boundaries -> {
      configuredClassNames.clear();
      for (RuntimeApi.CorrelationConsumerBoundary boundary : boundaries) {
        if (!shouldInstrument.test(boundary.fqcn())) {
          return RuntimeApi.InstallationResult.failed(
              "correlation_boundary_outside_instrumentation_scope");
        }
        configuredClassNames.add(boundary.fqcn());
      }
      if (!instrumentation.isRetransformClassesSupported()) {
        return RuntimeApi.InstallationResult.failed(
            "correlation_boundary_retransformation_unsupported");
      }
      return retransformConfiguredBoundaryClasses(instrumentation, configuredClassNames);
    });
  }

  private RuntimeApi.InstallationResult retransformConfiguredBoundaryClasses(
      Instrumentation instrumentation,
      Set<String> configuredClassNames
  ) {
    for (Class<?> loadedType : instrumentation.getAllLoadedClasses()) {
      if (!configuredClassNames.contains(loadedType.getName())) {
        continue;
      }
      if (!instrumentation.isModifiableClass(loadedType)) {
        return RuntimeApi.InstallationResult.failed(
            "correlation_boundary_class_not_modifiable");
      }
      try {
        instrumentation.retransformClasses(loadedType);
      } catch (Exception exception) {
        return RuntimeApi.InstallationResult.failed(
            "correlation_boundary_installation_failed");
      }
    }
    return RuntimeApi.InstallationResult.success();
  }

  private void installJdkCorrelationTransformer(Instrumentation instrumentation) {
    AgentBuilder builder = new AgentBuilder.Default()
        .disableClassFormatChanges()
        .ignore(ElementMatchers.none());
    if (instrumentation.isRetransformClassesSupported()) {
      builder = builder.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
    }
    ResettableClassFileTransformer transformer = builder
        .type(ElementMatchers.named("java.util.concurrent.AbstractExecutorService")
            .or(ElementMatchers.named("java.util.concurrent.ThreadPoolExecutor"))
            .or(ElementMatchers.named("java.util.concurrent.ScheduledThreadPoolExecutor"))
            .or(ElementMatchers.named("java.util.concurrent.ForkJoinPool")))
        .transform((builderValue, typeDescription, classLoader, module, protectionDomain) ->
            builderValue.visit(Advice.to(JdkExecutorCorrelationAdvice.class).on(
                ElementMatchers.named("execute")
                    .or(ElementMatchers.named("submit"))
                    .or(ElementMatchers.named("schedule"))
                    .or(ElementMatchers.named("scheduleAtFixedRate"))
                    .or(ElementMatchers.named("scheduleWithFixedDelay")))))
        .installOn(instrumentation);
    activeTransformers.add(transformer);
    retransformJdkExecutors(instrumentation);
  }

  private void retransformConfiguredClasses(
      Instrumentation instrumentation,
      Predicate<String> shouldInstrument
  ) {
    if (!instrumentation.isRetransformClassesSupported()) {
      return;
    }
    for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
      if (!shouldInstrument.test(loadedClass.getName())
          || !instrumentation.isModifiableClass(loadedClass)) {
        continue;
      }
      try {
        instrumentation.retransformClasses(loadedClass);
      } catch (Exception exception) {
        System.err.println("[probe-agent] Failed to retransform " + loadedClass.getName()
            + ": " + exception.getMessage());
      }
    }
  }

  private void retransformJdkExecutors(Instrumentation instrumentation) {
    if (!instrumentation.isRetransformClassesSupported()) {
      return;
    }
    for (Class<?> loadedType : instrumentation.getAllLoadedClasses()) {
      if (!isJdkExecutor(loadedType.getName()) || !instrumentation.isModifiableClass(loadedType)) {
        continue;
      }
      try {
        instrumentation.retransformClasses(loadedType);
        transformedClassNames.add(loadedType.getName());
      } catch (Exception exception) {
        System.err.println("[probe-agent] Failed to retransform JDK executor "
            + loadedType.getName() + ": " + exception.getMessage());
      }
    }
  }

  private boolean isJdkExecutor(String className) {
    return className.equals("java.util.concurrent.AbstractExecutorService")
        || className.equals("java.util.concurrent.ThreadPoolExecutor")
        || className.equals("java.util.concurrent.ScheduledThreadPoolExecutor")
        || className.equals("java.util.concurrent.ForkJoinPool");
  }

  private boolean removeOwnedTransformers() {
    boolean removed = true;
    for (ResettableClassFileTransformer transformer : activeTransformers) {
      if (!activeInstrumentation.removeTransformer(transformer)) {
        removed = false;
      }
    }
    return removed;
  }

  private List<String> restoreTransformedClasses() {
    List<String> nonRestorable = new ArrayList<>();
    for (Class<?> loadedClass : activeInstrumentation.getAllLoadedClasses()) {
      if (!transformedClassNames.contains(loadedClass.getName())) {
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

  public record DeactivationResult(
      boolean transformersRemoved,
      List<String> nonRestorableClasses
  ) {
    public DeactivationResult {
      nonRestorableClasses = List.copyOf(nonRestorableClasses);
    }
  }
}
