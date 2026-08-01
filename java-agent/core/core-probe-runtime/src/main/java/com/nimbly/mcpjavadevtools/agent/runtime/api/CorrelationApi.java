package com.nimbly.mcpjavadevtools.agent.runtime.api;

import com.nimbly.mcpjavadevtools.agent.runtime.CorrelationContext;
import com.nimbly.mcpjavadevtools.agent.runtime.CorrelationEventConsumerAdapter;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;

/** Stable correlation boundary for instrumentation and lifecycle consumers. */
public final class CorrelationApi {
  private CorrelationApi() {}

  public static BindingSnapshot current() {
    return toApi(CorrelationContext.current());
  }

  public static void configureFromSystemProperties() {
    CorrelationEventConsumerAdapter.configureFromSystemProperties();
  }

  public static void restore(BindingSnapshot snapshot) {
    CorrelationContext.restore(
        snapshot == null ? null : new CorrelationContext.BindingSnapshot(
            snapshot.executionId(), snapshot.sessionId(), snapshot.keyType(), snapshot.keyFingerprint()));
  }

  public static Runnable wrap(Runnable task) {
    return CorrelationContext.wrap(task);
  }

  public static <T> Callable<T> wrap(Callable<T> task) {
    return CorrelationContext.wrap(task);
  }

  public static BindingSnapshot bindFromEventArguments(Object[] arguments, Method origin,
                                                        boolean annotationDriven) {
    return toApi(CorrelationEventConsumerAdapter
        .bindFromEventArguments(arguments, origin, annotationDriven));
  }

  public static KclBindingResult bindFromKclArguments(Object[] arguments) {
    var result = CorrelationEventConsumerAdapter.bindFromKclArguments(arguments);
    return new KclBindingResult(toApi(result.previous()), result.outcome(), result.reasonCode(),
        result.correlationSessionId(), result.correlationExecutionId());
  }

  public static void restoreKcl(BindingSnapshot previous) {
    restore(previous);
  }

  public static void recordKclOutcome(KclBindingResult result) {
    RuntimeApi.recordKclBindingOutcome(result.outcome(), result.reasonCode(),
        result.correlationSessionId(), result.correlationExecutionId());
  }

  public static List<RuntimeApi.CorrelationConsumerBoundary> configuredConsumerBoundaries() {
    return CorrelationEventConsumerAdapter
        .configuredConsumerBoundaries().stream()
        .map(value -> new RuntimeApi.CorrelationConsumerBoundary(value.id(), value.fqcn(), value.method(),
            value.parameterTypes(), value.eventArgumentIndex()))
        .toList();
  }

  private static BindingSnapshot toApi(
      CorrelationContext.BindingSnapshot value) {
    return value == null ? null : new BindingSnapshot(value.executionId(), value.sessionId(),
        value.keyType(), value.keyFingerprint());
  }

  public record BindingSnapshot(String executionId, String sessionId, String keyType,
                                String keyFingerprint) {}

  public record KclBindingResult(BindingSnapshot previous, String outcome, String reasonCode,
                                 String correlationSessionId, String correlationExecutionId) {}
}
