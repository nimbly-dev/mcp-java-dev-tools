package com.nimbly.mcpjavadevtools.agent.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Process-local correlation identity bound by an event-consumer adapter. */
public final class CorrelationContext {
  private static final int MAX_KEY_LENGTH = 256;
  private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();

  private CorrelationContext() {}

  public static void bind(String keyType, String rawKey) {
    bind("", "", keyType, rawKey);
  }

  private static void bind(String executionId, String sessionId, String keyType, String rawKey) {
    String normalizedKey = normalizeKey(rawKey);
    if (normalizedKey == null || normalizedKey.isEmpty()) {
      CURRENT.remove();
      return;
    }
    CURRENT.set(new Binding(
        normalizeMetadata(executionId),
        normalizeMetadata(sessionId),
        normalizeKeyType(keyType),
        fingerprint(normalizedKey)
    ));
  }

  /** Entry point for supported event-consumer adapters to bind an event envelope identity. */
  public static void bindEventEnvelope(String sessionId, String keyType, String rawKey) {
    bind("", sessionId, keyType, rawKey);
  }

  public static void bindEventEnvelope(String executionId, String sessionId, String keyType, String rawKey) {
    bind(executionId, sessionId, keyType, rawKey);
  }

  public static void bindEventEnvelope(String keyType, String rawKey) {
    bindEventEnvelope("", keyType, rawKey);
  }

  /** Runs an event-consumer callback with a fresh context and always restores the prior context. */
  public static void runWithEventContext(String sessionId, String keyType, String rawKey, Runnable task) {
    runWithEventContext("", sessionId, keyType, rawKey, task);
  }

  public static void runWithEventContext(String executionId, String sessionId, String keyType, String rawKey, Runnable task) {
    Binding previous = CURRENT.get();
    bindEventEnvelope(executionId, sessionId, keyType, rawKey);
    try {
      task.run();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

  public static void runWithEventContext(String keyType, String rawKey, Runnable task) {
    runWithEventContext("", keyType, rawKey, task);
  }

  public static BindingSnapshot current() {
    Binding binding = CURRENT.get();
    return binding == null
        ? null
        : new BindingSnapshot(binding.executionId, binding.sessionId, binding.keyType, binding.keyFingerprint);
  }

  public static Runnable wrap(Runnable task) {
    Binding captured = CURRENT.get();
    return () -> {
      Binding previous = CURRENT.get();
      if (captured == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(captured);
      }
      try {
        task.run();
      } finally {
        if (previous == null) {
          CURRENT.remove();
        } else {
          CURRENT.set(previous);
        }
      }
    };
  }

  public static void clear() {
    CURRENT.remove();
  }

  public static void restore(BindingSnapshot snapshot) {
    if (snapshot == null) {
      CURRENT.remove();
      return;
    }
    CURRENT.set(new Binding(
        snapshot.executionId(),
        snapshot.sessionId(),
        snapshot.keyType(),
        snapshot.keyFingerprint()));
  }

  private static String normalizeKey(String value) {
    if (value == null) return "";
    String normalized = value.trim();
    return normalized.length() > MAX_KEY_LENGTH ? null : normalized;
  }

  private static String normalizeMetadata(String value) {
    return value == null ? "" : value.trim();
  }

  private static String normalizeKeyType(String value) {
    String normalized = normalizeMetadata(value);
    return normalized.equals("traceId") || normalized.equals("requestId") || normalized.equals("messageId")
        ? normalized
        : "";
  }

  private static String fingerprint(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required for correlation fingerprints", exception);
    }
  }

  public record BindingSnapshot(String executionId, String sessionId, String keyType, String keyFingerprint) {}

  private record Binding(String executionId, String sessionId, String keyType, String keyFingerprint) {}
}
