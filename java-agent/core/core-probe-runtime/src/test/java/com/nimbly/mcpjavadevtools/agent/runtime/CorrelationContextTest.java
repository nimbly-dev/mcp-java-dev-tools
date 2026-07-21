package com.nimbly.mcpjavadevtools.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

class CorrelationContextTest {
  @AfterEach
  void clearContext() {
    CorrelationContext.clear();
  }

  @Test
  void bindsOnlySanitizedFingerprintAndClears() {
    CorrelationContext.bind("messageId", " 96 ");

    CorrelationContext.BindingSnapshot snapshot = CorrelationContext.current();

    assertNotNull(snapshot);
    assertEquals("", snapshot.sessionId());
    assertEquals("messageId", snapshot.keyType());
    assertEquals("sha256:7b1a278f5abe8e9da907fc9c29dfd432d60dc76e17b0fabab659d2a508bc65c4", snapshot.keyFingerprint());
    CorrelationContext.clear();
    assertNull(CorrelationContext.current());
  }

  @Test
  void oversizedKeyFailsClosed() {
    CorrelationContext.bindEventEnvelope("messageId", "x".repeat(257));

    assertNull(CorrelationContext.current());
  }

  @Test
  void wrappedTaskRestoresPreviousContext() throws Exception {
    ProbeRuntime.runWithCorrelationContext("messageId", "96", () -> {
      assertNotNull(CorrelationContext.current());
      Runnable wrapped = CorrelationContext.wrap(() -> assertNotNull(CorrelationContext.current()));
      wrapped.run();
    });
    assertNull(CorrelationContext.current());
  }

  @Test
  void wrappedCallablePropagatesOnlyTheBoundContext() throws Exception {
    ProbeRuntime.runWithCorrelationContext("session-async", "messageId", "96", () -> {
      Callable<CorrelationContext.BindingSnapshot> task = CorrelationContext.wrap(
          CorrelationContext::current);
      try {
        assertEquals("session-async", task.call().sessionId());
      } catch (Exception exception) {
        throw new IllegalStateException(exception);
      }
    });
  }

  @Test
  void productionEntryPointTagsLineHitAndRestoresContext() {
    int before = ProbeRuntime.runtimeLineHitEvents().size();

    ProbeRuntime.runWithCorrelationContext(
        "session-1",
        "messageId",
        "96",
        () -> ProbeRuntime.hitLineByClassMethod("example.Task", "process", 46)
    );

    assertEquals(before + 1, ProbeRuntime.runtimeLineHitEvents().size());
    RuntimeLineHitEvent event = ProbeRuntime.runtimeLineHitEvents().get(before);
    assertEquals("example.Task#process:46", event.lineKey());
    assertEquals(1, event.hitCount());
    assertEquals("session-1", event.correlationSessionId());
    assertEquals("messageId", event.keyType());
    assertNull(CorrelationContext.current());
  }

  @Test
  void supportedConsumerAdapterBindsOnlyConsumerCallback() {
    CorrelationEventConsumerAdapter.consume(
        new CorrelationEventEnvelope("execution-2", "session-2", "messageId", "96"),
        () -> {
          assertEquals("session-2", CorrelationContext.current().sessionId());
          ProbeRuntime.hitLineByClassMethod("example.Task", "consume", 47);
        }
    );
    assertNull(CorrelationContext.current());
  }

  @Test
  void agentConsumerBoundaryBindsConventionBasedEventArguments() {
    record Event(int jobId) {}
    CorrelationEventConsumerAdapter.configure("$.jobId", "session-auto", "execution-auto");
    CorrelationContext.BindingSnapshot previous =
        CorrelationEventConsumerAdapter.bindFromEventArguments(
            new Object[] {new Event(96)});
    try {
      ProbeRuntime.hitLineByClassMethod("example.Task", "autoConsume", 50);
      RuntimeLineHitEvent event = ProbeRuntime.runtimeLineHitEvents("session-auto", 0, 10).stream()
          .filter(candidate -> candidate.lineKey().equals("example.Task#autoConsume:50"))
          .findFirst()
          .orElseThrow();
      assertEquals("execution-auto", event.correlationExecutionId());
    } finally {
      CorrelationEventConsumerAdapter.restore(previous);
    }
    assertNull(CorrelationContext.current());
    CorrelationEventConsumerAdapter.configure("", "", "");
  }

  @Test
  void agentConsumerBoundaryBindsNestedEventKeyPath() {
    record Payload(String orderId) {}
    record Envelope(Payload detail) {}
    CorrelationEventConsumerAdapter.configure(
        "$.detail.orderId", "session-nested", "execution-nested");
    CorrelationContext.BindingSnapshot previous =
        CorrelationEventConsumerAdapter.bindFromEventArguments(
            new Object[] {new Envelope(new Payload("order-123"))});
    try {
      assertEquals("session-nested", CorrelationContext.current().sessionId());
      ProbeRuntime.hitLineByClassMethod("example.Task", "nestedConsume", 51);
      RuntimeLineHitEvent event = ProbeRuntime.runtimeLineHitEvents("session-nested", 0, 10).stream()
          .filter(candidate -> candidate.lineKey().equals("example.Task#nestedConsume:51"))
          .findFirst()
          .orElseThrow();
      assertEquals("execution-nested", event.correlationExecutionId());
    } finally {
      CorrelationEventConsumerAdapter.restore(previous);
      CorrelationEventConsumerAdapter.configure("", "", "");
    }
    assertNull(CorrelationContext.current());
  }

  @Test
  void repeatedHotLineHitsRemainOneBoundedAggregate() {
    ProbeRuntime.runWithCorrelationContext("session-hot", "messageId", "96", () -> {
      for (int i = 0; i < 1_000; i++) {
        ProbeRuntime.hitLineByClassMethod("example.Hot", "consume", 48);
      }
    });
    RuntimeLineHitEvent event = ProbeRuntime.runtimeLineHitEvents("session-hot", 0, 10).stream()
        .filter(candidate -> candidate.lineKey().equals("example.Hot#consume:48"))
        .findFirst()
        .orElseThrow();
    assertEquals(1_000, event.hitCount());
    assertEquals(event.sequence() + 999, event.lastSequence());
  }

  @Test
  void executionIdentityPreventsCrossRunAggregation() {
    ProbeRuntime.runWithCorrelationContext("execution-a", "session-shared", "messageId", "96", () ->
        ProbeRuntime.hitLineByClassMethod("example.Shared", "consume", 49));
    ProbeRuntime.runWithCorrelationContext("execution-b", "session-shared", "messageId", "96", () ->
        ProbeRuntime.hitLineByClassMethod("example.Shared", "consume", 49));

    var events = ProbeRuntime.runtimeLineHitEvents("session-shared", 0, 10).stream()
        .filter(candidate -> candidate.lineKey().equals("example.Shared#consume:49"))
        .toList();
    assertEquals(2, events.size());
    assertEquals("execution-a", events.get(0).correlationExecutionId());
    assertEquals("execution-b", events.get(1).correlationExecutionId());
    assertEquals(1, events.get(0).hitCount());
    assertEquals(1, events.get(1).hitCount());
  }

  @Test
  void correlationLeaseRejectsOverlappingExecutionUntilReleased() {
    assertTrue(CorrelationEventConsumerAdapter.tryConfigure(
        "$.jobId", "session-a", "execution-a", 10_000L));
    assertFalse(CorrelationEventConsumerAdapter.tryConfigure(
        "$.jobId", "session-b", "execution-b", 10_000L));
    assertTrue(CorrelationEventConsumerAdapter.release("execution-a"));
    assertTrue(CorrelationEventConsumerAdapter.tryConfigure(
        "$.jobId", "session-b", "execution-b", 10_000L));
    assertTrue(CorrelationEventConsumerAdapter.release("execution-b"));
  }
}
