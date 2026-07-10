export function joinUrl(baseUrl: string, path: string): string {
  const b = baseUrl.replace(/\/+$/, "");
  const p = path.startsWith("/") ? path : `/${path}`;
  return `${b}${p}`;
}

export function probeUnreachableMessage(url: string, err: unknown): string {
  const raw = err instanceof Error ? err.message : String(err);
  return (
    `Probe endpoint unreachable: ${url}\n` +
    `Error: ${raw}\n` +
    `Check active probe mapping in .mcpjvm/probe-config.json for the running service. ` +
    `If the active probe port is unknown, ask the user for the mapped probe port (for example 9193).`
  );
}

export function parseProbeSnapshot(structuredContent: Record<string, unknown>): {
  key?: string;
  hitCount?: number;
  lastHitEpoch?: number;
} {
  const response = structuredContent.response as Record<string, unknown> | undefined;
  const json = response?.json as Record<string, unknown> | undefined;
  const probe =
    json && typeof json.probe === "object" && json.probe !== null
      ? (json.probe as Record<string, unknown>)
      : json;
  const out: { key?: string; hitCount?: number; lastHitEpoch?: number } = {};
  const key = probe?.key;
  const hitCount = probe?.hitCount;
  const lastHitEpoch = probe?.lastHitEpoch;
  if (typeof key === "string") out.key = key;
  if (typeof hitCount === "number") out.hitCount = hitCount;
  if (typeof lastHitEpoch === "number") out.lastHitEpoch = lastHitEpoch;
  return out;
}
