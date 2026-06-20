package com.nimbly.mcpjavadevtools.agent.capture;

final class CaptureKeyParser {
  private CaptureKeyParser() {}

  static String toMethodKey(String key) {
    if (key == null || key.isBlank()) return null;
    ParsedLineKey parsed = parseLineKey(key);
    if (parsed != null) return parsed.methodKey;
    String trimmed = key.trim();
    return trimmed.contains("#") ? trimmed : null;
  }

  private static ParsedLineKey parseLineKey(String key) {
    if (key == null || key.isBlank()) return null;
    int hash = key.lastIndexOf('#');
    int colon = key.lastIndexOf(':');
    if (hash <= 0 || colon <= hash + 1 || colon == key.length() - 1) return null;
    String methodKey = key.substring(0, colon);
    String linePart = key.substring(colon + 1);
    int lineNumber;
    try {
      lineNumber = Integer.parseInt(linePart);
    } catch (NumberFormatException ignored) {
      return null;
    }
    if (lineNumber <= 0) return null;
    return new ParsedLineKey(methodKey);
  }

  private static final class ParsedLineKey {
    private final String methodKey;

    private ParsedLineKey(String methodKey) {
      this.methodKey = methodKey;
    }
  }
}

