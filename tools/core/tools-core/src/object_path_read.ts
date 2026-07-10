function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isValidPathToken(value: string): boolean {
  return /^[A-Za-z0-9_-]+$/.test(value);
}

function tokenizePath(path: string): Array<string | number> | null {
  const trimmed = path.trim();
  if (trimmed.length === 0) return null;
  const tokens: Array<string | number> = [];
  let i = 0;
  while (i < trimmed.length) {
    const char = trimmed[i];
    if (char === ".") {
      i += 1;
      continue;
    }
    if (char === "[") {
      const close = trimmed.indexOf("]", i);
      if (close < 0) return null;
      const rawIndex = trimmed.slice(i + 1, close).trim();
      if (!/^\d+$/.test(rawIndex)) return null;
      tokens.push(Number(rawIndex));
      i = close + 1;
      continue;
    }
    let end = i;
    while (end < trimmed.length && trimmed[end] !== "." && trimmed[end] !== "[") {
      end += 1;
    }
    const token = trimmed.slice(i, end).trim();
    if (!isValidPathToken(token)) return null;
    tokens.push(token);
    i = end;
  }
  return tokens.length > 0 ? tokens : null;
}

export function readValueByPath(input: unknown, path: string): unknown {
  const tokens = tokenizePath(path);
  if (!tokens) return undefined;
  let cursor: unknown = input;
  for (const token of tokens) {
    if (typeof token === "number") {
      if (!Array.isArray(cursor) || token < 0 || token >= cursor.length) return undefined;
      cursor = cursor[token];
      continue;
    }
    if (!isRecord(cursor)) return undefined;
    cursor = cursor[token];
  }
  return cursor;
}

