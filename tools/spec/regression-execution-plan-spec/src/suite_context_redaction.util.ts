const SECRET_KEY_PATTERN = /(?:token|secret|password|authorization|api[-_]?key|bearer)/i;
const SECRET_VALUE_PATTERN =
  /(?:\bbearer\s+[a-z0-9\-._~+/]+=*|\bghp_[a-z0-9]+|\bsk-[a-z0-9]{12,}|\bapi[_-]?key\b|\bpassword\b)/i;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function collectResolvedExplicitSecretKeys(
  value: unknown,
  explicitSecretPaths: ReadonlySet<string>,
  parentPath: string | null = null,
  found: Set<string> = new Set<string>(),
): Set<string> {
  if (Array.isArray(value)) {
    for (const item of value) {
      collectResolvedExplicitSecretKeys(item, explicitSecretPaths, parentPath, found);
    }
    return found;
  }
  if (!isRecord(value)) {
    return found;
  }
  for (const [key, child] of Object.entries(value)) {
    const currentPath = parentPath ? `${parentPath}.${key}` : key;
    const explicitSecretKey = explicitSecretPaths.has(currentPath)
      ? currentPath
      : explicitSecretPaths.has(key)
        ? key
        : null;
    if (explicitSecretKey) {
      found.add(explicitSecretKey);
      continue;
    }
    collectResolvedExplicitSecretKeys(child, explicitSecretPaths, currentPath, found);
  }
  return found;
}

export function sanitizeSuitePersistedContext(
  value: unknown,
  explicitSecretPaths: ReadonlySet<string>,
  parentPath: string | null = null,
): unknown {
  if (typeof value === "string" && SECRET_VALUE_PATTERN.test(value)) {
    return "[REDACTED]";
  }
  if (Array.isArray(value)) {
    return value.map((item) => sanitizeSuitePersistedContext(item, explicitSecretPaths, parentPath));
  }
  if (!isRecord(value)) {
    return value;
  }
  const output: Record<string, unknown> = {};
  for (const [key, child] of Object.entries(value)) {
    const currentPath = parentPath ? `${parentPath}.${key}` : key;
    const isExplicitSecret = explicitSecretPaths.has(key) || explicitSecretPaths.has(currentPath);
    const isRedactionMetadata = currentPath === "redaction" || currentPath.startsWith("redaction.");
    const isPatternSecret = !isRedactionMetadata && (SECRET_KEY_PATTERN.test(key) || SECRET_KEY_PATTERN.test(currentPath));
    if (isExplicitSecret || isPatternSecret) {
      continue;
    }
    output[key] = sanitizeSuitePersistedContext(child, explicitSecretPaths, currentPath);
  }
  return output;
}

export function buildResolvedSecretRedactionMeta(args: {
  resolvedContext: Record<string, unknown>;
  explicitSecretPaths: ReadonlySet<string>;
}): { resolvedSecretKeyCount: number; resolvedSecretKeysOmitted: string[] } | undefined {
  const resolvedSecretKeysOmitted = [...collectResolvedExplicitSecretKeys(args.resolvedContext, args.explicitSecretPaths)].sort(
    (a, b) => a.localeCompare(b),
  );
  if (resolvedSecretKeysOmitted.length === 0) {
    return undefined;
  }
  return {
    resolvedSecretKeyCount: resolvedSecretKeysOmitted.length,
    resolvedSecretKeysOmitted,
  };
}
