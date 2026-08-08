/**
 * Performance request construction and health-check support.
 */
import { deepResolvePlaceholderValue } from "@tools-core/placeholder_resolution";
import net from "node:net";
import type { PerformanceEntrypoint } from "./parse_performance_contract";
import { parseStringRecord } from "./parse_performance_contract";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asTrimmedString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value.trim() : undefined;
}

async function isTcpReachable(args: {
  host: string;
  port: number;
  timeoutMs: number;
}): Promise<boolean> {
  return await new Promise<boolean>((resolve) => {
    const socket = new net.Socket();
    let settled = false;
    const finish = (ok: boolean): void => {
      if (settled) return;
      settled = true;
      socket.destroy();
      resolve(ok);
    };
    socket.setTimeout(args.timeoutMs, () => finish(false));
    socket.once("error", () => finish(false));
    socket.connect(args.port, args.host, () => finish(true));
  });
}

async function waitForTargetPort(args: {
  requestUrl: string;
  timeoutMs: number;
}): Promise<boolean> {
  let target: URL;
  try {
    target = new URL(args.requestUrl);
  } catch {
    return false;
  }
  const port = target.port
    ? Number(target.port)
    : target.protocol === "https:"
      ? 443
      : target.protocol === "http:"
        ? 80
        : NaN;
  if (!target.hostname || !Number.isInteger(port) || port <= 0 || port > 65535) return false;

  const deadline = Date.now() + Math.max(1_000, args.timeoutMs);
  while (Date.now() < deadline) {
    if (
      await isTcpReachable({
        host: target.hostname,
        port,
        timeoutMs: Math.min(500, Math.max(100, args.timeoutMs)),
      })
    ) {
      return true;
    }
    await new Promise((resolve) => setTimeout(resolve, Math.min(250, Math.max(100, args.timeoutMs / 8))));
  }
  return false;
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
  runtimeLifecyclePrepared?: boolean;
  mcpInvoke: (args: {
    toolName: string;
    input: Record<string, unknown>;
  }) => Promise<{ structuredContent: Record<string, unknown> }>;
}): Promise<{ ok: true } | { ok: false; reasonCode: string; requiredUserAction: string[] }> {
  const healthCheckPath = args.entrypoint.transport.healthCheckPath;
  if (args.runtimeLifecyclePrepared !== true && !healthCheckPath) return { ok: true };
  const targetRequest = await buildTransportRequest({
    entrypoint: args.entrypoint,
    providedContext: args.providedContext,
    ...(typeof args.requestTimeoutMs === "number"
      ? { requestTimeoutMs: args.requestTimeoutMs }
      : {}),
  });
  if ("error" in targetRequest) {
    return {
      ok: false,
      reasonCode: "external_healthcheck_failed",
      requiredUserAction: [`Fix target readiness request: ${targetRequest.error}`],
    };
  }
  if (
    args.runtimeLifecyclePrepared === true &&
    !(await waitForTargetPort({
      requestUrl: String(targetRequest.request.url ?? ""),
      timeoutMs: args.requestTimeoutMs ?? 3_000,
    }))
  ) {
    return {
      ok: false,
      reasonCode: "external_healthcheck_failed",
      requiredUserAction: [
        "Ensure the performance target HTTP port is reachable before starting the workload.",
      ],
    };
  }
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
