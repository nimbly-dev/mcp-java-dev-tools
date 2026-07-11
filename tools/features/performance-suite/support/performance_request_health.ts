/**
 * Performance request construction and health-check support.
 */
import { deepResolvePlaceholderValue } from "@tools-core/placeholder_resolution";
import type { PerformanceEntrypoint } from "./parse_performance_contract";
import { parseStringRecord } from "./parse_performance_contract";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asTrimmedString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value.trim() : undefined;
}

export async function buildTransportRequest(args: {
  entrypoint: PerformanceEntrypoint;
  providedContext: Record<string, unknown>;
  requestTimeoutMs?: number;
}): Promise<{ request: Record<string, unknown>; wrappedOnly: boolean } | { error: string }> {
  try {
    const requestSpec = deepResolvePlaceholderValue(
      args.entrypoint.request,
      args.providedContext,
    ) as Record<string, unknown>;
    const transportSpec = deepResolvePlaceholderValue(
      args.entrypoint.transport,
      args.providedContext,
    ) as Record<string, unknown>;
    const baseUrl = asTrimmedString(transportSpec.baseUrl);
    const method = asTrimmedString(requestSpec.method);
    const requestPath = asTrimmedString(requestSpec.path);
    if (!baseUrl || !method || !requestPath) {
      return { error: "entrypoint transport baseUrl/method/path are required" };
    }
    const url = new URL(requestPath, baseUrl);
    const queryTemplate = isRecord(requestSpec.queryTemplate)
      ? requestSpec.queryTemplate
      : undefined;
    if (queryTemplate) {
      for (const [key, value] of Object.entries(queryTemplate)) {
        if (typeof value !== "undefined" && value !== null) {
          url.searchParams.set(key, String(value));
        }
      }
    }
    const headers = {
      ...(parseStringRecord(transportSpec.defaultHeaders) ?? {}),
      ...(parseStringRecord(requestSpec.headers) ?? {}),
    };
    return {
      request: {
        method,
        url: url.toString(),
        ...(Object.keys(headers).length > 0 ? { headers } : {}),
        ...(typeof requestSpec.body !== "undefined" ? { body: requestSpec.body } : {}),
        ...(typeof args.requestTimeoutMs === "number" ? { timeoutMs: args.requestTimeoutMs } : {}),
      },
      wrappedOnly: transportSpec.wrappedOnly !== false,
    };
  } catch (error) {
    return { error: error instanceof Error ? error.message : String(error) };
  }
}

export async function verifyHealthcheck(args: {
  entrypoint: PerformanceEntrypoint;
  providedContext: Record<string, unknown>;
  requestTimeoutMs?: number;
  mcpInvoke: (args: {
    toolName: string;
    input: Record<string, unknown>;
  }) => Promise<{ structuredContent: Record<string, unknown> }>;
}): Promise<{ ok: true } | { ok: false; reasonCode: string; requiredUserAction: string[] }> {
  const healthCheckPath = args.entrypoint.transport.healthCheckPath;
  if (!healthCheckPath) return { ok: true };
  const request = await buildTransportRequest({
    entrypoint: {
      ...args.entrypoint,
      request: {
        method: "GET",
        path: healthCheckPath,
      },
    },
    providedContext: args.providedContext,
    ...(typeof args.requestTimeoutMs === "number"
      ? { requestTimeoutMs: args.requestTimeoutMs }
      : {}),
  });
  if ("error" in request) {
    return {
      ok: false,
      reasonCode: "external_healthcheck_failed",
      requiredUserAction: [`Fix healthcheck request: ${request.error}`],
    };
  }
  const out = await args.mcpInvoke({
    toolName: "transport_execute",
    input: {
      request: request.request,
      wrappedOnly: request.wrappedOnly,
    },
  });
  if (out.structuredContent.status !== "pass") {
    return {
      ok: false,
      reasonCode: "external_healthcheck_failed",
      requiredUserAction: [
        "Ensure the performance target runtime healthcheck is reachable before execution.",
      ],
    };
  }
  return { ok: true };
}
