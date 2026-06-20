package com.nimbly.mcpjavadevtools.agent.capture;

public final class CaptureValueView {
  public final String value;
  public final boolean truncated;
  public final int originalLength;
  public final boolean redacted;

  CaptureValueView(String value, boolean truncated, int originalLength, boolean redacted) {
    this.value = value;
    this.truncated = truncated;
    this.originalLength = originalLength;
    this.redacted = redacted;
  }
}

