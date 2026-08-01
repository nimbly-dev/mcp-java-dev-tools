package com.nimbly.mcpjavadevtools.agent.failure;

import java.util.List;

/** Bounded causal-chain section retained from a parsed Java stack trace. */
public record FailureExceptionSection(
    String exceptionType,
    boolean suppressed,
    boolean elidedFrames,
    List<FailureFrame> frames
) {
  public FailureExceptionSection {
    frames = List.copyOf(frames);
  }
}
