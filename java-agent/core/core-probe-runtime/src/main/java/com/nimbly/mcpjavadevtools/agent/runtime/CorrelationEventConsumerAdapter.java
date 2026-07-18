package com.nimbly.mcpjavadevtools.agent.runtime;

import java.util.Objects;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Transport-neutral production boundary for supported event-consumer adapters. */
public final class CorrelationEventConsumerAdapter {
  private static volatile String configuredKeyPath = "";
  private static volatile String configuredSessionId = "";
  private static volatile String configuredExecutionId = "";
  private static volatile long leaseExpiresAtEpochMs = 0L;

  private CorrelationEventConsumerAdapter() {}

  public static synchronized void configure(String keyPath, String sessionId, String executionId) {
    if ((keyPath == null || keyPath.isBlank())
        && (sessionId == null || sessionId.isBlank())
        && (executionId == null || executionId.isBlank())) {
      configuredKeyPath = "";
      configuredSessionId = "";
      configuredExecutionId = "";
      leaseExpiresAtEpochMs = 0L;
      return;
    }
    tryConfigure(keyPath, sessionId, executionId, 300_000L);
  }

  public static synchronized boolean tryConfigure(
      String keyPath, String sessionId, String executionId, long leaseTtlMs
  ) {
    String owner = executionId == null ? "" : executionId.trim();
    long now = System.currentTimeMillis();
    if (leaseExpiresAtEpochMs > now && !configuredExecutionId.isEmpty()
        && !configuredExecutionId.equals(owner)) {
      return false;
    }
    configuredKeyPath = keyPath == null ? "" : keyPath.trim();
    configuredSessionId = sessionId == null ? "" : sessionId.trim();
    configuredExecutionId = owner;
    leaseExpiresAtEpochMs = now + Math.max(1_000L, Math.min(300_000L, leaseTtlMs));
    return true;
  }

  public static synchronized boolean release(String executionId) {
    String owner = executionId == null ? "" : executionId.trim();
    if (!configuredExecutionId.isEmpty() && !configuredExecutionId.equals(owner)
        && leaseExpiresAtEpochMs > System.currentTimeMillis()) return false;
    configuredKeyPath = "";
    configuredSessionId = "";
    configuredExecutionId = "";
    leaseExpiresAtEpochMs = 0L;
    return true;
  }

  public static void configureFromSystemProperties() {
    configure(
        System.getProperty("mcp.correlation.eventKeyPath", ""),
        System.getProperty("mcp.correlation.sessionId", ""),
        System.getProperty("mcp.correlation.executionId", ""));
  }

  public static void consume(CorrelationEventEnvelope envelope, Runnable consumerCallback) {
    Objects.requireNonNull(envelope, "envelope");
    Objects.requireNonNull(consumerCallback, "consumerCallback");
    ProbeRuntime.runWithCorrelationContext(
        envelope.correlationExecutionId(),
        envelope.correlationSessionId(),
        envelope.keyType(),
        envelope.keyValue(),
        consumerCallback
    );
  }

  /** Binds a convention-based event envelope at an instrumented consumer entry point. */
  public static CorrelationContext.BindingSnapshot bindFromEventArguments(Object[] arguments) {
    CorrelationContext.BindingSnapshot previous = CorrelationContext.current();
    if (arguments == null) return previous;
    for (Object argument : arguments) {
      CorrelationEventEnvelope envelope = envelopeFrom(argument);
      if (envelope == null) continue;
      ProbeRuntime.bindCorrelationContext(
          envelope.correlationExecutionId(),
          envelope.correlationSessionId(),
          envelope.keyType(),
          envelope.keyValue());
      return previous;
    }
    return previous;
  }

  public static void restore(CorrelationContext.BindingSnapshot previous) {
    CorrelationContext.restore(previous);
  }

  private static CorrelationEventEnvelope envelopeFrom(Object value) {
    if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean)
      return null;
    String keyValue = valueAtPath(value, configuredKeyPath);
    if (keyValue == null || keyValue.isBlank()) return null;
    String keyType = stringProperty(value, "correlationKeyType", "keyType");
    if (keyType == null || keyType.isBlank()) keyType = "messageId";
    String sessionId = stringProperty(value, "correlationSessionId", "sessionId");
    String executionId = stringProperty(value, "correlationExecutionId", "executionId");
    if (sessionId == null) sessionId = configuredSessionId;
    if (executionId == null) executionId = configuredExecutionId;
    return new CorrelationEventEnvelope(executionId, sessionId, keyType, keyValue);
  }

  private static String valueAtPath(Object value, String path) {
    if (path == null || path.isBlank() || !path.startsWith("$.")) return null;
    Object current = value;
    for (String segment : path.substring(2).split("\\.")) {
      if (current == null) return null;
      String result = stringProperty(current, segment);
      if (result == null) return null;
      current = result;
    }
    return current == null ? null : String.valueOf(current).trim();
  }

  private static String stringProperty(Object value, String... names) {
    for (String name : names) {
      try {
        Method getter = value.getClass().getMethod(name);
        Object result = getter.invoke(value);
        if (result != null) return String.valueOf(result).trim();
      } catch (ReflectiveOperationException ignored) {
        try {
          Field field = value.getClass().getDeclaredField(name);
          field.setAccessible(true);
          Object result = field.get(value);
          if (result != null) return String.valueOf(result).trim();
        } catch (ReflectiveOperationException ignoredField) {
          // Try the next convention.
        }
      }
    }
    return null;
  }
}
