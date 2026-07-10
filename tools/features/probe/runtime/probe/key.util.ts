export function isLineKey(key: string): boolean {
  return /#[^:]+:\d+$/.test(key);
}

export function resolveProbeKey(key: string, lineHint?: number): string {
  if (typeof lineHint !== "number") return key;
  if (isLineKey(key)) return key;
  return `${key}:${lineHint}`;
}

export function classifyExecutionHitStrictLine(key: string, hit: boolean): string {
  if (!isLineKey(key)) return "not_hit";
  return hit ? "line_hit" : "not_hit";
}

export function classifyReproStatusStrictLine(key: string, hit: boolean): string {
  if (!isLineKey(key)) return "line_key_required";
  return hit ? "line_reproduced" : "line_not_reproduced";
}
