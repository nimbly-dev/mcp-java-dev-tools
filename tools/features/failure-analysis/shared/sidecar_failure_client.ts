import { fetchJson } from "@tools-core/http";
import { clampInt, DEFAULT_PROBE_TIMEOUT_MS, HARD_MAX_PROBE_TIMEOUT_MS } from "@tools-core/safety";

function failureUrl(baseUrl: string, path: string): string {
  return new URL(path, baseUrl.endsWith("/") ? baseUrl : `${baseUrl}/`).toString();
}

export async function postFailureSidecar(
  baseUrl: string,
  path: string,
  payload: Record<string, unknown>,
  requestedTimeoutMs?: number,
  authorization?: string,
): Promise<{ status: number; json: Record<string, unknown> | null }> {
  const timeoutMs = clampInt(
    requestedTimeoutMs ?? DEFAULT_PROBE_TIMEOUT_MS,
    1_000,
    HARD_MAX_PROBE_TIMEOUT_MS,
  );
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (authorization?.trim()) {
    headers.authorization = authorization.trim();
  }
  const response = await fetchJson(failureUrl(baseUrl, path), {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
    timeoutMs,
  });
  const json =
    response.json && typeof response.json === "object"
      ? (response.json as Record<string, unknown>)
      : null;
  return { status: response.status, json };
}
