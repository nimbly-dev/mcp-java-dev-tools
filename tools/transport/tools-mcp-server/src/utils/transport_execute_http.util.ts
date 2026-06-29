import { deriveNextActionCode, normalizeReasonMeta } from "@/utils/failure_diagnostics.util";

function toBodyPreview(value: string): string {
  const trimmed = value.trim();
  if (trimmed.length <= 2048) return trimmed;
  return trimmed.slice(0, 2048);
}

function asString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
}

function asNumber(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function hasHeader(headers: Record<string, string>, key: string): boolean {
  const target = key.toLowerCase();
  return Object.keys(headers).some((entry) => entry.toLowerCase() === target);
}

function normalizeHttpBody(args: {
  bodyRaw: unknown;
  headers: Record<string, string>;
}): { ok: true; body?: string } | { ok: false; reason: string } {
  const { bodyRaw, headers } = args;
  if (typeof bodyRaw === "undefined" || bodyRaw === null) {
    return { ok: true };
  }
  const asText = asString(bodyRaw);
  if (asText) {
    return { ok: true, body: asText };
  }
  if (typeof bodyRaw === "object" && !Array.isArray(bodyRaw)) {
    if (!hasHeader(headers, "content-type")) {
      headers["content-type"] = "application/json";
    }
    return { ok: true, body: JSON.stringify(bodyRaw) };
  }
  if (Array.isArray(bodyRaw)) {
    if (!hasHeader(headers, "content-type")) {
      headers["content-type"] = "application/json";
    }
    return { ok: true, body: JSON.stringify(bodyRaw) };
  }
  return {
    ok: false,
    reason: "http transport request.body must be a non-empty string, object, array, null, or undefined.",
  };
}

function elapsedMs(startEpochMs: number): number {
  const delta = Date.now() - startEpochMs;
  if (!Number.isFinite(delta) || delta <= 0) return 1;
  return Math.max(1, Math.round(delta));
}

export async function executeHttpTransportRequest(args: {
  request: Record<string, unknown>;
  includeBody?: boolean;
}): Promise<Record<string, unknown>> {
  const startEpochMs = Date.now();
  const method = asString(args.request.method);
  const url = asString(args.request.url);
  if (!method || !url) {
    return {
      status: "blocked_invalid",
      reasonCode: "http_payload_invalid",
      nextActionCode: deriveNextActionCode("http_payload_invalid"),
      reasonMeta: normalizeReasonMeta({
        failedStep: "transport_execute_http_payload",
      }),
      errorMessage: "http transport requires request.method and request.url.",
      durationMs: elapsedMs(startEpochMs),
      protocol: "http",
    };
  }

  const timeoutMs = Math.max(1, Math.round(asNumber(args.request.timeoutMs) ?? 20000));
  const ctrl = new AbortController();
  const handle = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const headers: Record<string, string> = {};
    const requestHeaders = args.request.headers;
    if (requestHeaders && typeof requestHeaders === "object" && !Array.isArray(requestHeaders)) {
      for (const [k, v] of Object.entries(requestHeaders as Record<string, unknown>)) {
        const value = asString(v);
        if (value) headers[k] = value;
      }
    }
    const bodyNormalized = normalizeHttpBody({
      bodyRaw: args.request.body,
      headers,
    });
    if (!bodyNormalized.ok) {
      return {
        status: "blocked_invalid",
        reasonCode: "http_payload_invalid",
        nextActionCode: deriveNextActionCode("http_payload_invalid"),
        reasonMeta: normalizeReasonMeta({
          failedStep: "transport_execute_http_payload",
        }),
        errorMessage: bodyNormalized.reason,
        durationMs: elapsedMs(startEpochMs),
        protocol: "http",
      };
    }
    const response = await fetch(url, {
      method: method.toUpperCase(),
      headers,
      ...(bodyNormalized.body ? { body: bodyNormalized.body } : {}),
      signal: ctrl.signal,
    });
    const text = await response.text();
    return {
      status: response.status >= 200 && response.status < 400 ? "pass" : "fail_http",
      protocol: "http",
      statusCode: response.status,
      durationMs: elapsedMs(startEpochMs),
      headers: Object.fromEntries(response.headers.entries()),
      ...(args.includeBody === true ? { body: text } : {}),
      bodyPreview: toBodyPreview(text),
    };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return {
      status: "blocked_runtime",
      reasonCode: "transport_request_failed",
      nextActionCode: deriveNextActionCode("transport_request_failed"),
      reasonMeta: normalizeReasonMeta({
        failedStep: "transport_execute_http",
        url,
      }),
      errorMessage: message,
      durationMs: elapsedMs(startEpochMs),
      protocol: "http",
    };
  } finally {
    clearTimeout(handle);
  }
}
