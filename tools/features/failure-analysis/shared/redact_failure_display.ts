const SENSITIVE_VALUE_PATTERN =
  /bearer\s+\S+|basic\s+\S+|(?:password|secret|token|authorization|cookie)\s*[:=]\s*\S+/i;
const LONG_TOKEN_PATTERN = /\b[A-Za-z0-9_-]{40,}\b/;

export function redactFailureFingerprint(value: unknown): unknown {
  if (!value || typeof value !== "object" || Array.isArray(value)) return value;
  const fingerprint = { ...(value as Record<string, unknown>) };
  if (typeof fingerprint.normalizedMessage === "string") {
    fingerprint.normalizedMessage = redactMessage(fingerprint.normalizedMessage);
  }
  return fingerprint;
}

function redactMessage(message: string): string {
  if (SENSITIVE_VALUE_PATTERN.test(message) || LONG_TOKEN_PATTERN.test(message))
    return "<redacted>";
  return message;
}
