package com.nimbly.mcpjavadevtools.agent.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.nimbly.mcpjavadevtools.agent.runtime.ProbeRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FailureTraceAnalyzerTest {
  @BeforeEach
  void registerApplicationScope() {
    ProbeRuntime.registerApplicationClassResolver(className -> className.startsWith("com.example.app."));
  }

  @AfterEach
  void clearApplicationScope() {
    ProbeRuntime.registerApplicationClassResolver(null);
    ProbeRuntime.registerLoadedClassResolver(null);
  }

  @Test
  void parsesNestedCauseAndSelectsNearestApplicationFrame() {
    String trace = "java.util.concurrent.CompletionException: request 123456 failed\n"
        + "  at java.base/java.util.concurrent.CompletableFuture.join(CompletableFuture.java:2118)\n"
        + "Caused by: com.example.app.OrderFailure: order 123456 failed\n"
        + "  at com.example.app.OrderService.submit(OrderService.java:42)\n"
        + "  at org.springframework.web.Controller.invoke(Controller.java:8)\n"
        + "Caused by: java.lang.IllegalStateException: id 550e8400-e29b-41d4-a716-446655440000\n"
        + "  at com.example.app.InventoryClient.reserve(InventoryClient.java:73)\n";

    FailureTraceAnalysis result = FailureTraceAnalyzer.analyze(trace);

    assertEquals("java.util.concurrent.CompletionException", result.fingerprint().exceptionType());
    assertEquals("java.lang.IllegalStateException", result.fingerprint().rootCauseType());
    assertEquals("com.example.app.OrderService", result.fingerprint().nearestApplicationFrame().className());
    assertEquals(42, result.fingerprint().nearestApplicationFrame().lineNumber());
    assertEquals("request {number} failed", result.fingerprint().normalizedMessage());
    assertFalse(result.fingerprint().complete());
    assertTrue(result.reasons().contains("class_not_loaded"));
    assertEquals(2, result.investigationCandidates().size());
    assertEquals("org.springframework.web.Controller", result.dependencyBoundary().className());
  }

  @Test
  void marksAFrameWithoutSourceLineAsIncomplete() {
    String trace = "com.example.app.OrderFailure: failed\n"
        + "  at com.example.app.OrderService.submit(Unknown Source)\n";

    FailureTraceAnalysis result = FailureTraceAnalyzer.analyze(trace);

    assertFalse(result.fingerprint().complete());
    assertTrue(result.reasons().contains("source_line_missing"));
  }

  @Test
  void comparesOnlyTheRequiredReproductionKey() {
    String trace = "com.example.app.OrderFailure: different message 123456\n"
        + "  at com.example.app.OrderService.submit(OrderService.java:42)\n"
        + "Caused by: java.lang.IllegalStateException: changed\n";
    FailureFingerprint observed = FailureTraceAnalyzer.analyze(trace).fingerprint();

    FailureComparison comparison = FailureComparison.compare(
        "com.example.app.OrderFailure",
        "java.lang.IllegalStateException",
        "com.example.app.OrderService#submit",
        observed);

    assertEquals("matched", comparison.outcome());
  }

  @Test
  void parsesWrappersSuppressedSectionsAndModuleQualifiedFrames() {
    String trace = "java.util.concurrent.ExecutionException: failed at 2026-08-01T12:34:56Z\n"
        + "  at java.base/java.util.concurrent.FutureTask.get(FutureTask.java:204)\n"
        + "Suppressed: java.io.IOException: audit 123456\n"
        + "  at com.example.audit.AuditWriter.write(AuditWriter.java:11)\n"
        + "Caused by: java.lang.reflect.InvocationTargetException\n"
        + "  at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)\n"
        + "Caused by: com.example.app.OrderFailure: request 550e8400-e29b-41d4-a716-446655440000\n"
        + "  at com.example.app.OrderService$$SpringCGLIB$$0.submit(OrderService.java:42)\n"
        + "  ... 3 more\n";

    FailureTraceAnalysis result = FailureTraceAnalyzer.analyze(trace);

    assertEquals("java.util.concurrent.ExecutionException", result.fingerprint().exceptionType());
    assertEquals("com.example.app.OrderFailure", result.fingerprint().rootCauseType());
    assertEquals("com.example.app.OrderService$$SpringCGLIB$$0", result.fingerprint().nearestApplicationFrame().className());
    assertEquals("failed at {timestamp}", result.fingerprint().normalizedMessage());
    assertTrue(result.reasons().contains("elided_frames_present"));
    assertEquals(1, result.investigationCandidates().size());
    assertEquals(4, result.exceptionSections().size());
    assertTrue(result.exceptionSections().get(1).suppressed());
    assertEquals("com.example.app.OrderFailure", result.exceptionSections().get(3).exceptionType());
  }

  @Test
  void returnsExplicitIncompleteFactsForMalformedTrace() {
    FailureTraceAnalysis result = FailureTraceAnalyzer.analyze("not a Java stack trace");

    assertFalse(result.fingerprint().complete());
    assertEquals(List.of("trace_exception_missing"), result.reasons());
    assertNull(result.fingerprint().nearestApplicationFrame());
  }

  @Test
  void parsesAnUncaughtThreadPrefix() {
    String trace = "Exception in thread \"main\" java.lang.IllegalStateException: failed\n"
        + "  at com.example.app.OrderService.submit(OrderService.java:42)\n";

    FailureTraceAnalysis result = FailureTraceAnalyzer.analyze(trace);

    assertEquals("java.lang.IllegalStateException", result.fingerprint().exceptionType());
    assertEquals("com.example.app.OrderService", result.fingerprint().nearestApplicationFrame().className());
  }

  @Test
  void treatsClassesOutsideTheConfiguredApplicationScopeAsDependencies() {
    String trace = "java.lang.IllegalStateException: failed\n"
        + "  at io.netty.handler.codec.Decoder.decode(Decoder.java:42)\n"
        + "  at com.example.app.OrderService.submit(OrderService.java:51)\n";

    FailureTraceAnalysis result = FailureTraceAnalyzer.analyze(trace);

    assertEquals("com.example.app.OrderService", result.fingerprint().nearestApplicationFrame().className());
    assertEquals("io.netty.handler.codec.Decoder", result.dependencyBoundary().className());
  }

  @Test
  void distinguishesEveryRuntimeComparisonMismatch() {
    FailureFingerprint observed = new FailureFingerprint(
        "com.example.app.OrderFailure",
        "java.lang.IllegalStateException",
        new FailureFrame("com.example.app.OrderService", "submit", "OrderService.java", 42,
            "application", null, null, List.of(), null),
        "",
        true,
        List.of());

    assertEquals("target_reached_no_exception", FailureComparison.compare(
        "com.example.app.OrderFailure", "java.lang.IllegalStateException", "com.example.app.OrderService#submit", null
    ).outcome());
    assertEquals("different_exception", FailureComparison.compare(
        "com.example.app.OtherFailure", "java.lang.IllegalStateException", "com.example.app.OrderService#submit", observed
    ).outcome());
    assertEquals("different_root_cause", FailureComparison.compare(
        "com.example.app.OrderFailure", "java.lang.IllegalArgumentException", "com.example.app.OrderService#submit", observed
    ).outcome());
    assertEquals("different_application_frame", FailureComparison.compare(
        "com.example.app.OrderFailure", "java.lang.IllegalStateException", "com.example.app.OtherService#submit", observed
    ).outcome());
  }

  @Test
  void enrichesALoadedFrameWithCodeSourceAndUnambiguousMethodDescriptor() {
    String className = FailureTraceAnalyzerTest.class.getName();
    ProbeRuntime.registerApplicationClassResolver(candidate -> candidate.equals(className));
    String trace = "java.lang.IllegalStateException: failed\n"
        + "  at " + className
        + ".enrichesALoadedFrameWithCodeSourceAndUnambiguousMethodDescriptor(FailureTraceAnalyzerTest.java:1)\n";

    FailureFrame frame = FailureTraceAnalyzer.analyze(trace).fingerprint().nearestApplicationFrame();

    assertEquals("()V", frame.methodDescriptor());
    assertTrue(frame.codeSource().contains("core-probe-debug"));
    assertTrue(frame.codeSourceCandidates().get(0).contains("core-probe-debug"));
    assertNull(frame.resolutionReason());
  }

  @Test
  void reportsAmbiguousLoadedClassesInsteadOfSelectingOne() {
    String className = FailureTraceAnalyzerTest.class.getName();
    ProbeRuntime.registerApplicationClassResolver(candidate -> candidate.equals(className));
    ProbeRuntime.registerLoadedClassResolver(ignored -> List.of(
        FailureTraceAnalyzerTest.class,
        FailureTraceAnalyzerTest.class));
    try {
      String trace = "java.lang.IllegalStateException: failed\n"
          + "  at " + className + ".reportsAmbiguousLoadedClassesInsteadOfSelectingOne(FailureTraceAnalyzerTest.java:1)\n";

      FailureTraceAnalysis result = FailureTraceAnalyzer.analyze(trace);

      assertFalse(result.fingerprint().complete());
      assertEquals("loaded_class_ambiguous", result.fingerprint().nearestApplicationFrame().resolutionReason());
      assertTrue(result.reasons().contains("loaded_class_ambiguous"));
    } finally {
      ProbeRuntime.registerLoadedClassResolver(null);
    }
  }

  @Test
  void preservesLambdaAndByteBuddyFrameFormsWithoutGuessingClasspathFacts() {
    String trace = "java.lang.IllegalStateException: failed\n"
        + "  at com.example.app.OrderService$$ByteBuddy$$42.submit(OrderService.java:42)\n"
        + "  at com.example.app.OrderService.lambda$submit$0(OrderService.java:51)\n";

    FailureTraceAnalysis result = FailureTraceAnalyzer.analyze(trace);

    assertEquals("com.example.app.OrderService$$ByteBuddy$$42",
        result.fingerprint().nearestApplicationFrame().className());
    assertEquals("submit", result.fingerprint().nearestApplicationFrame().methodName());
    assertEquals(2, result.investigationCandidates().size());
    assertTrue(result.reasons().contains("class_not_loaded"));
  }
}
