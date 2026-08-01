package com.nimbly.mcpjavadevtools.agent.failure;

import java.util.List;

/** Deterministic failure identity used for runtime reproduction comparison. */
public record FailureFingerprint(
    String exceptionType,
    String rootCauseType,
    FailureFrame nearestApplicationFrame,
    String normalizedMessage,
    boolean complete,
    List<String> incompletenessReasons
) {
  public FailureFingerprint {
    incompletenessReasons = List.copyOf(incompletenessReasons);
  }
}
