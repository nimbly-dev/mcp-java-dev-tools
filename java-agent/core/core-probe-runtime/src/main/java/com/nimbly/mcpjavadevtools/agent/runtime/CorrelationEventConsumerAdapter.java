package com.nimbly.mcpjavadevtools.agent.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/** Transport-neutral production boundary for supported event-consumer adapters. */
public final class CorrelationEventConsumerAdapter {
  private static final String KCL_PROCESS_RECORDS_INPUT =
      "software.amazon.kinesis.lifecycle.events.ProcessRecordsInput";
  /** Public correlation path selecting KCL's consistent batch partition-key policy. */
  public static final String KCL_PARTITION_KEY_PATH = "$.kcl.partitionKey";
  private static volatile String configuredKeyPath = "";
  private static volatile String configuredSessionId = "";
  private static volatile String configuredExecutionId = "";
  private static volatile List<CorrelationConsumerBoundary> configuredConsumerBoundaries = List.of();
  private static volatile long leaseExpiresAtEpochMs = 0L;

  private CorrelationEventConsumerAdapter() {}

  public static synchronized void configure(String keyPath, String sessionId, String executionId) {
    if ((keyPath == null || keyPath.isBlank())
        && (sessionId == null || sessionId.isBlank())
        && (executionId == null || executionId.isBlank())) {
      configuredKeyPath = "";
      configuredSessionId = "";
      configuredExecutionId = "";
      configuredConsumerBoundaries = List.of();
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

  public static void configureConsumerBoundaries(List<CorrelationConsumerBoundary> boundaries) {
    configuredConsumerBoundaries = boundaries == null ? List.of() : List.copyOf(boundaries);
  }

  public static List<CorrelationConsumerBoundary> configuredConsumerBoundaries() {
    return configuredConsumerBoundaries;
  }

  public static synchronized boolean release(String executionId) {
    String owner = executionId == null ? "" : executionId.trim();
    if (!configuredExecutionId.isEmpty() && !configuredExecutionId.equals(owner)
        && leaseExpiresAtEpochMs > System.currentTimeMillis()) return false;
    configuredKeyPath = "";
    configuredSessionId = "";
    configuredExecutionId = "";
    configuredConsumerBoundaries = List.of();
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
      if (envelope != null) {
        bind(envelope);
        return previous;
      }
    }
    return previous;
  }

  /** Binds only the configured event argument for an exact selector, including overload identity. */
  public static CorrelationContext.BindingSnapshot bindFromEventArguments(
      Object[] arguments, Method origin) {
    return bindFromEventArguments(arguments, origin, false);
  }

  /** Binds an exact selector while preserving annotation-driven consumer fallback. */
  public static CorrelationContext.BindingSnapshot bindFromEventArguments(
      Object[] arguments, Method origin, boolean annotationDriven) {
    CorrelationContext.BindingSnapshot previous = CorrelationContext.current();
    CorrelationConsumerBoundary boundary = findBoundary(origin);
    if (boundary == null) {
      if (!annotationDriven && isConfiguredMethodFamily(origin)) return previous;
      return bindFromEventArgumentsAfterSnapshot(arguments, previous);
    }
    if (arguments == null || boundary.eventArgumentIndex() >= arguments.length) return previous;
    CorrelationEventEnvelope envelope = envelopeFrom(arguments[boundary.eventArgumentIndex()]);
    if (envelope != null) bind(envelope);
    return previous;
  }

  /** Binds a consistent KCL batch using its partition key as the correlation key. */
  public static KclBindingResult bindFromKclArguments(Object[] arguments) {
    CorrelationContext.BindingSnapshot previous = CorrelationContext.current();
    Object input = findKclProcessRecordsInput(arguments);
    if (input == null) return KclBindingResult.notApplicable(previous);
    if (!configuredKeyPath.isBlank() && !KCL_PARTITION_KEY_PATH.equals(configuredKeyPath)) {
      return KclBindingResult.refused(previous, "kcl_event_key_path_unsupported");
    }
    KclPartitionKeyResult partitionKeyResult = consistentPartitionKey(input);
    if (partitionKeyResult.partitionKey() == null) {
      return KclBindingResult.refused(previous, partitionKeyResult.reasonCode());
    }
    // Preserve the existing key-type contract; the KCL partition key is the message identity value.
    ProbeRuntime.bindCorrelationContext(
        configuredExecutionId,
        configuredSessionId,
        "messageId",
        partitionKeyResult.partitionKey());
    return KclBindingResult.bound(previous);
  }

  public static void restore(CorrelationContext.BindingSnapshot previous) {
    CorrelationContext.restore(previous);
  }

  private static CorrelationContext.BindingSnapshot bindFromEventArgumentsAfterSnapshot(
      Object[] arguments, CorrelationContext.BindingSnapshot previous) {
    if (arguments == null) return previous;
    for (Object argument : arguments) {
      CorrelationEventEnvelope envelope = envelopeFrom(argument);
      if (envelope != null) {
        bind(envelope);
        return previous;
      }
    }
    return previous;
  }

  private static CorrelationConsumerBoundary findBoundary(Method origin) {
    if (origin == null) return null;
    for (CorrelationConsumerBoundary boundary : configuredConsumerBoundaries) {
      if (boundary.matches(origin)) return boundary;
    }
    return null;
  }

  private static boolean isConfiguredMethodFamily(Method origin) {
    if (origin == null) return false;
    for (CorrelationConsumerBoundary boundary : configuredConsumerBoundaries) {
      if (boundary.fqcn().equals(origin.getDeclaringClass().getName())
          && boundary.method().equals(origin.getName())) {
        return true;
      }
    }
    return false;
  }

  private static void bind(CorrelationEventEnvelope envelope) {
    ProbeRuntime.bindCorrelationContext(
        envelope.correlationExecutionId(),
        envelope.correlationSessionId(),
        envelope.keyType(),
        envelope.keyValue());
  }

  private static Object findKclProcessRecordsInput(Object[] arguments) {
    if (arguments == null) return null;
    for (Object argument : arguments) {
      if (argument != null && KCL_PROCESS_RECORDS_INPUT.equals(argument.getClass().getName())) {
        return argument;
      }
    }
    return null;
  }

  private static KclPartitionKeyResult consistentPartitionKey(Object input) {
    Object recordsValue = propertyValue(input, "records");
    if (!(recordsValue instanceof Iterable<?> records)) {
      return KclPartitionKeyResult.refused("kcl_records_unavailable");
    }
    String partitionKey = null;
    for (Object record : records) {
      if (record == null) {
        return KclPartitionKeyResult.refused("kcl_partition_key_missing");
      }
      String currentPartitionKey = stringProperty(record, "partitionKey");
      if (currentPartitionKey == null || currentPartitionKey.isBlank()) {
        return KclPartitionKeyResult.refused("kcl_partition_key_missing");
      }
      if (partitionKey == null) {
        partitionKey = currentPartitionKey;
      } else if (!partitionKey.equals(currentPartitionKey)) {
        return KclPartitionKeyResult.refused("kcl_mixed_partition_keys");
      }
    }
    return partitionKey == null
        ? KclPartitionKeyResult.refused("kcl_batch_empty")
        : KclPartitionKeyResult.bound(partitionKey);
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
      current = propertyValue(current, segment);
      if (current == null) return null;
    }
    return current == null ? null : String.valueOf(current).trim();
  }

  private static String stringProperty(Object value, String... names) {
    for (String name : names) {
      Object result = propertyValue(value, name);
      if (result != null) return String.valueOf(result).trim();
    }
    return null;
  }

  private static Object propertyValue(Object value, String name) {
    try {
      Method getter = value.getClass().getMethod(name);
      Object result = getter.invoke(value);
      if (result != null) return result;
    } catch (ReflectiveOperationException ignored) {
      try {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        Object result = field.get(value);
        if (result != null) return result;
      } catch (ReflectiveOperationException ignoredField) {
        return null;
      }
    }
    return null;
  }

  public record KclBindingResult(
      CorrelationContext.BindingSnapshot previous,
      String outcome,
      String reasonCode,
      String correlationSessionId,
      String correlationExecutionId) {
    private static KclBindingResult bound(CorrelationContext.BindingSnapshot previous) {
      return new KclBindingResult(
          previous, "bound", "ok", configuredSessionId, configuredExecutionId);
    }

    private static KclBindingResult notApplicable(CorrelationContext.BindingSnapshot previous) {
      return new KclBindingResult(
          previous, "not_applicable", "kcl_input_missing", configuredSessionId, configuredExecutionId);
    }

    private static KclBindingResult refused(
        CorrelationContext.BindingSnapshot previous, String reasonCode) {
      return new KclBindingResult(
          previous, "refused", reasonCode, configuredSessionId, configuredExecutionId);
    }
  }

  private record KclPartitionKeyResult(String partitionKey, String reasonCode) {
    private static KclPartitionKeyResult bound(String partitionKey) {
      return new KclPartitionKeyResult(partitionKey, "ok");
    }

    private static KclPartitionKeyResult refused(String reasonCode) {
      return new KclPartitionKeyResult(null, reasonCode);
    }
  }
}
