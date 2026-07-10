function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export type InvalidPlaceholderToken = {
  invalidToken: string;
};

export function normalizePlaceholderSyntaxInString(value: string): {
  normalized: string;
  invalidToken?: string;
} {
  const normalizedTripleBrace = value.replace(/\{\{\{\s*([A-Za-z0-9_.-]+)\s*\}\}\}/g, (_match, key: string) => `\${${key.trim()}}`);
  const normalizedDoubleBrace = normalizedTripleBrace.replace(/\{\{\s*([A-Za-z0-9_.-]+)\s*\}\}/g, (_match, key: string) => `\${${key.trim()}}`);
  const normalized = normalizedDoubleBrace.replace(/\$\{\s*([A-Za-z0-9_.-]+)\s*\}/g, (_match, key: string) => `\${${key.trim()}}`);
  const invalidMatch = normalized.match(/\{\{\{|\}\}\}|\{\{|\}\}|\$\{\s*\}|\$\{[^}]*$/);
  if (invalidMatch) {
    return {
      normalized,
      invalidToken: invalidMatch[0],
    };
  }
  return { normalized };
}

export function deepResolvePlaceholderValue(value: unknown, context: Record<string, unknown>): unknown {
  if (typeof value === "string") {
    const normalized = normalizePlaceholderSyntaxInString(value);
    if (typeof normalized.invalidToken === "string") {
      throw new Error(`invalid_placeholder:${normalized.invalidToken}`);
    }
    return normalized.normalized.replace(/\$\{([^}]+)\}/g, (_match, key) => {
      const resolved = context[key];
      if (typeof resolved === "undefined" || resolved === null) {
        throw new Error(`missing_context:${key}`);
      }
      return String(resolved);
    });
  }
  if (Array.isArray(value)) {
    return value.map((item) => deepResolvePlaceholderValue(item, context));
  }
  if (isRecord(value)) {
    const output: Record<string, unknown> = {};
    for (const [key, child] of Object.entries(value)) {
      output[key] = deepResolvePlaceholderValue(child, context);
    }
    return output;
  }
  return value;
}
