package com.nimbly.mcpjavadevtools.agent.failure;

import java.util.List;

/** Bounded Sidecar result for a pasted Java stack trace. */
public record FailureTraceAnalysis(
    FailureFingerprint fingerprint,
    List<FailureFrame> investigationCandidates,
    FailureFrame dependencyBoundary,
    List<FailureExceptionSection> exceptionSections,
    List<String> reasons
) {
  public FailureTraceAnalysis {
    investigationCandidates = List.copyOf(investigationCandidates);
    exceptionSections = List.copyOf(exceptionSections);
    reasons = List.copyOf(reasons);
  }
}
