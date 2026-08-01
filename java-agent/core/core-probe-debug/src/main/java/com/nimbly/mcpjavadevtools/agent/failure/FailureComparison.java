package com.nimbly.mcpjavadevtools.agent.failure;

import java.util.List;

/** Deterministic comparison of expected and observed Failure Lens fingerprints. */
public record FailureComparison(
    String outcome,
    FailureFingerprint observedFingerprint,
    List<String> reasons
) {
  public FailureComparison {
    reasons = List.copyOf(reasons);
  }

  public static FailureComparison compare(
      String expectedExceptionType,
      String expectedRootCauseType,
      String expectedNearestApplicationMethodKey,
      FailureFingerprint observed
  ) {
    if (observed == null) return noException();
    if (!expectedExceptionType.equals(observed.exceptionType())) return differentException(observed);
    if (!expectedRootCauseType.equals(observed.rootCauseType())) return differentRootCause(observed);
    if (!matchesMethodKey(expectedNearestApplicationMethodKey, observed.nearestApplicationFrame())) {
      return differentApplicationFrame(observed);
    }
    return new FailureComparison("matched", observed, List.of());
  }

  private static FailureComparison noException() {
    return new FailureComparison("target_reached_no_exception", null, List.of("observed_exception_missing"));
  }

  private static FailureComparison differentException(FailureFingerprint observed) {
    return new FailureComparison("different_exception", observed, List.of("exception_type_mismatch"));
  }

  private static FailureComparison differentRootCause(FailureFingerprint observed) {
    return new FailureComparison("different_root_cause", observed, List.of("root_cause_type_mismatch"));
  }

  private static FailureComparison differentApplicationFrame(FailureFingerprint observed) {
    return new FailureComparison("different_application_frame", observed, List.of("application_frame_mismatch"));
  }

  private static boolean matchesMethodKey(String expected, FailureFrame observed) {
    if (expected == null || expected.isBlank() || observed == null) return false;
    return expected.equals(observed.methodKey());
  }
}
