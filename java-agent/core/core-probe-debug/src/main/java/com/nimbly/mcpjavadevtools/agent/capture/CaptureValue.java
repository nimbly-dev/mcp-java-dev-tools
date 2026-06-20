package com.nimbly.mcpjavadevtools.agent.capture;

final class CaptureValue {
  private final String storedValue;
  private final boolean storedTruncated;
  private final int originalLength;
  private final boolean redacted;

  private CaptureValue(String storedValue, boolean storedTruncated, int originalLength, boolean redacted) {
    this.storedValue = storedValue;
    this.storedTruncated = storedTruncated;
    this.originalLength = originalLength;
    this.redacted = redacted;
  }

  static CaptureValue fromNormalized(String value, boolean redacted, int maxChars) {
    if (value == null) value = "null";
    int originalLength = value.length();
    boolean truncated = originalLength > maxChars;
    String stored = truncated ? value.substring(0, maxChars) + "...(truncated)" : value;
    return new CaptureValue(stored, truncated, originalLength, redacted);
  }

  static CaptureValue synthetic(String value, boolean truncated, boolean redacted) {
    return new CaptureValue(value, truncated, value.length(), redacted);
  }

  CaptureValueView toView(int maxChars) {
    if (storedValue == null) {
      return new CaptureValueView("null", storedTruncated, originalLength, redacted);
    }
    if (maxChars >= storedValue.length()) {
      return new CaptureValueView(storedValue, storedTruncated, originalLength, redacted);
    }
    String preview = storedValue.substring(0, maxChars) + "...(preview_truncated)";
    return new CaptureValueView(preview, true, originalLength, redacted);
  }
}

