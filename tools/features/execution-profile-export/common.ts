export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function asString(value: unknown): string | undefined {
  if (typeof value !== "string") {
    return undefined;
  }
  const trimmed = value.trim();
  if (!trimmed) {
    return undefined;
  }
  return trimmed;
}

export function escapePsSingleQuoted(value: string): string {
  return value.replaceAll("'", "''");
}

export function escapeShSingleQuoted(value: string): string {
  return value.replaceAll("'", "'\"'\"'");
}

export function toShellEnvKey(input: string): string {
  const raw = input.trim().replace(/^env[.:_-]/i, "");
  const normalized = raw
    .replace(/([a-z0-9])([A-Z])/g, "$1_$2")
    .replace(/[^A-Za-z0-9_]/g, "_")
    .replace(/_+/g, "_")
    .replace(/^_+|_+$/g, "")
    .toUpperCase();
  const safe = normalized.length > 0 ? normalized : "REQUIRED_INPUT";
  return /^[A-Z_]/.test(safe) ? safe : `V_${safe}`;
}
