package com.nimbly.mcpjavadevtools.agent.runtime.api;

import com.nimbly.mcpjavadevtools.agent.runtime.CorrelationBoundaryInstaller;
import com.nimbly.mcpjavadevtools.agent.runtime.CorrelationEventConsumerAdapter;
import com.nimbly.mcpjavadevtools.agent.runtime.ProbeRuntime;
import java.util.List;
import java.util.function.Function;

/** Stable capability surface for runtime consumers outside the runtime module. */
public final class RuntimeApi {
  private RuntimeApi() {}

  public static void configure(String mode, String actuatorId, String targetKey,
                               boolean returnBoolean, String probeId) {
    ProbeRuntime.configure(
        mode, actuatorId, targetKey, returnBoolean, probeId);
  }

  public static void registerLoadedClassResolver(Function<String, List<Class<?>>> resolver) {
    ProbeRuntime.registerLoadedClassResolver(resolver);
  }

  public static List<Class<?>> loadedClasses(String className) {
    return ProbeRuntime.loadedClasses(className);
  }

  public static void registerApplicationClassResolver(Function<String, Boolean> resolver) {
    ProbeRuntime.registerApplicationClassResolver(resolver);
  }

  public static boolean isApplicationClass(String className) {
    return ProbeRuntime.isApplicationClass(className);
  }

  public static RuntimeApi.ActuationState armSession(String sessionId, String actuatorId, String targetKey,
                                          boolean returnBoolean, long ttlMs) {
    return toApi(ProbeRuntime.armSession(
        sessionId, actuatorId, targetKey, returnBoolean, ttlMs));
  }

  public static RuntimeApi.ActuationState disarmSession(String sessionId) {
    return toApi(ProbeRuntime.disarmSession(sessionId));
  }

  public static RuntimeApi.ActuationState sessionState(String sessionId) {
    return toApi(ProbeRuntime.sessionState(sessionId));
  }

  public static RuntimeApi.ActuationState actuationState() {
    return toApi(ProbeRuntime.actuationState());
  }

  public static KeyStatus keyStatus(String key) {
    var value = ProbeRuntime.keyStatus(key);
    return new KeyStatus(value.key(), value.hitCount(), value.lastHitEpoch(),
        value.lineResolvable(), value.lineValidation());
  }

  public static RuntimeState runtimeState() {
    var value = ProbeRuntime.runtimeState();
    return new RuntimeState(
        toApi(value.actuation()),
        value.serverEpoch(),
        value.applicationType() == null ? null : new RuntimeStringSignal(
            value.applicationType().value, value.applicationType().source, value.applicationType().confidence),
        value.appPort() == null ? null : new RuntimePortSignal(
            value.appPort().value, value.appPort().source, value.appPort().confidence));
  }

  public static void registerResolvableLine(String className, String methodName, int lineNumber) {
    ProbeRuntime.registerResolvableLine(
        className, methodName, lineNumber);
  }

  public static void registerActuatableLine(String className, String methodName, int lineNumber) {
    ProbeRuntime.registerActuatableLine(
        className, methodName, lineNumber);
  }

  public static boolean isLineKey(String key) {
    return ProbeRuntime.isLineKey(key);
  }

  public static boolean isLineResolvableKey(String key) {
    return ProbeRuntime.isLineResolvableKey(key);
  }

  public static boolean isLineActuatableKey(String key) {
    return ProbeRuntime.isLineActuatableKey(key);
  }

  public static List<String> lineKeysForClass(String className) {
    return ProbeRuntime.lineKeysForClass(className);
  }

  public static void reset(String key) {
    ProbeRuntime.reset(key);
  }

  public static long minTtlMs() {
    return ProbeRuntime.minTtlMs();
  }

  public static long maxTtlMs() {
    return ProbeRuntime.maxTtlMs();
  }

  public static String runtimeInstanceId() {
    return ProbeRuntime.runtimeInstanceId();
  }

  public static long runtimeLineHitNextSequence() {
    return ProbeRuntime.runtimeLineHitNextSequence();
  }

  public static long runtimeLineHitStreamResetEpoch() {
    return ProbeRuntime.runtimeLineHitStreamResetEpoch();
  }

  public static RuntimeLineHitEventPage runtimeLineHitEventPage(String sessionId, long afterSequence, int limit) {
    var page = ProbeRuntime.runtimeLineHitEventPage(
        sessionId, afterSequence, limit);
    return new RuntimeLineHitEventPage(page.events().stream().map(RuntimeApi::toApi).toList(),
        page.lastDeliveredSequence(), page.hasMore());
  }

  public static CorrelationConfigureResult tryConfigureCorrelationContext(
      String sessionId, String executionId, String eventKeyPath, long leaseTtlMs,
      List<RuntimeApi.CorrelationConsumerBoundary> boundaries) {
    var result = ProbeRuntime.tryConfigureCorrelationContext(
        sessionId, executionId, eventKeyPath, leaseTtlMs,
        boundaries == null ? List.of() : boundaries.stream().map(RuntimeApi::toInternal).toList());
    return new CorrelationConfigureResult(result.configured(), result.reasonCode());
  }

  public static boolean releaseCorrelationContext(String executionId) {
    return ProbeRuntime.releaseCorrelationContext(executionId);
  }

  public static KclBindingStatus kclBindingStatus() {
    var value = ProbeRuntime.kclBindingStatus();
    return new KclBindingStatus(value.outcome(), value.reasonCode(), value.correlationSessionId(),
        value.correlationExecutionId(), value.observedAtEpochMs());
  }

  public static void recordKclBindingOutcome(String outcome, String reasonCode,
                                              String sessionId, String executionId) {
    ProbeRuntime.recordKclBindingOutcome(
        new CorrelationEventConsumerAdapter.KclBindingResult(
            null, outcome, reasonCode, sessionId, executionId));
  }

  public static void registerCorrelationBoundaryInstaller(BoundaryInstaller installer) {
    ProbeRuntime.registerCorrelationBoundaryInstaller(
        installer == null ? null : boundaries -> {
          InstallationResult result = installer.install(boundaries.stream().map(RuntimeApi::toApi).toList());
          return result.installed()
              ? CorrelationBoundaryInstaller.InstallationResult.success()
              : CorrelationBoundaryInstaller.InstallationResult.failed(
                  result.reasonCode());
        });
  }

  public static String escJson(String value) {
    return ProbeRuntime.escJson(value);
  }

  private static RuntimeApi.ActuationState toApi(
      com.nimbly.mcpjavadevtools.agent.runtime.model.ActuationState value) {
    return new RuntimeApi.ActuationState(value.mode(), value.sessionId(), value.actuatorId(), value.targetKey(),
        value.returnBoolean(), value.expiresAtEpoch(), value.scopeState(), value.activeSessionCount());
  }

  private static RuntimeApi.RuntimeLineHitEvent toApi(
      com.nimbly.mcpjavadevtools.agent.runtime.RuntimeLineHitEvent value) {
    return new RuntimeApi.RuntimeLineHitEvent(value.sequence(), value.lastSequence(), value.hitCount(),
        value.correlationExecutionId(), value.correlationSessionId(), value.probeId(), value.lineKey(),
        value.runtimeInstanceId(), value.timestampEpochMs(), value.firstTimestampEpochMs(),
        value.keyType(), value.keyFingerprint());
  }

  private static com.nimbly.mcpjavadevtools.agent.runtime.CorrelationConsumerBoundary toInternal(
      RuntimeApi.CorrelationConsumerBoundary value) {
    return new com.nimbly.mcpjavadevtools.agent.runtime.CorrelationConsumerBoundary(
        value.id(), value.fqcn(), value.method(), value.parameterTypes(), value.eventArgumentIndex());
  }

  private static RuntimeApi.CorrelationConsumerBoundary toApi(
      com.nimbly.mcpjavadevtools.agent.runtime.CorrelationConsumerBoundary value) {
    return new RuntimeApi.CorrelationConsumerBoundary(value.id(), value.fqcn(), value.method(),
        value.parameterTypes(), value.eventArgumentIndex());
  }

  public record ActuationState(String mode, String sessionId, String actuatorId, String targetKey,
                               Boolean returnBoolean, Long expiresAtEpoch, String scopeState,
                               int activeSessionCount) {}

  public record KeyStatus(String key, long hitCount, long lastHitEpoch, Boolean lineResolvable,
                          String lineValidation) {}

  public record RuntimeState(RuntimeApi.ActuationState actuation, long serverEpoch,
                             RuntimeStringSignal applicationType, RuntimePortSignal appPort) {}

  public record RuntimeStringSignal(String value, String source, double confidence) {}

  public record RuntimePortSignal(Integer value, String source, double confidence) {}

  public record RuntimeLineHitEvent(long sequence, long lastSequence, long hitCount,
                                    String correlationExecutionId, String correlationSessionId,
                                    String probeId, String lineKey, String runtimeInstanceId,
                                    long timestampEpochMs, long firstTimestampEpochMs,
                                    String keyType, String keyFingerprint) {}

  public record RuntimeLineHitEventPage(List<RuntimeApi.RuntimeLineHitEvent> events,
                                        long lastDeliveredSequence, boolean hasMore) {}

  public record CorrelationConfigureResult(boolean configured, String reasonCode) {}

  public record KclBindingStatus(String outcome, String reasonCode, String correlationSessionId,
                                 String correlationExecutionId, long observedAtEpochMs) {}

  public record CorrelationConsumerBoundary(String id, String fqcn, String method,
                                            List<String> parameterTypes, int eventArgumentIndex) {}

  @FunctionalInterface
  public interface BoundaryInstaller {
    RuntimeApi.InstallationResult install(List<RuntimeApi.CorrelationConsumerBoundary> boundaries);
  }

  public record InstallationResult(boolean installed, String reasonCode) {
    public static InstallationResult success() {
      return new InstallationResult(true, "ok");
    }

    public static InstallationResult failed(String reasonCode) {
      return new InstallationResult(false, reasonCode);
    }
  }
}
