import type {
  HttpTransportRequest,
  TransportAdapter,
  TransportExecuteInput,
  TransportExecutionResult,
  TransportProtocol,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_transport.model";
import {
  resolveHttpUrlMissingReasonMeta,
  synthesizeHttpUrl,
} from "./regression_http_request";

type McpToolInvoker = (args: {
  toolName: string;
  input: Record<string, unknown>;
}) => Promise<{
  structuredContent?: Record<string, unknown>;
}>;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
}

function asNumber(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function toBodyPreview(value: string): string {
  const trimmed = value.trim();
  if (trimmed.length <= 2048) return trimmed;
  return trimmed.slice(0, 2048);
}

function validateHttpPayload(payload: Record<string, unknown>): {
  ok: true;
  request: HttpTransportRequest;
} | {
  ok: false;
  reasonMeta: Record<string, unknown>;
} {
  const method = asString(payload.method);
  const url =
    asString(payload.url) ??
    synthesizeHttpUrl({
      url: payload.url,
      apiBaseUrl: payload.apiBaseUrl,
      pathTemplate: payload.pathTemplate,
      path: payload.path,
    });
  if (!method || !url) {
    const missingFields = [
      ...(method ? [] : ["method"]),
      ...(url ? [] : ["url"]),
    ];
    const urlReasonMeta = !url
      ? resolveHttpUrlMissingReasonMeta({
          pathTemplate: payload.pathTemplate,
          path: payload.path,
        })
      : undefined;
    return {
      ok: false,
      reasonMeta: {
        missingFields,
        cause: method ? (urlReasonMeta?.cause ?? "url_missing") : (!url ? (urlReasonMeta?.cause ?? "url_missing") : "method_missing"),
        ...(urlReasonMeta?.pathTemplate ? { pathTemplate: urlReasonMeta.pathTemplate } : {}),
        ...(urlReasonMeta?.path ? { path: urlReasonMeta.path } : {}),
      },
    };
  }
  const headers: Record<string, string> = {};
  if (isRecord(payload.headers)) {
    for (const [k, v] of Object.entries(payload.headers)) {
      const val = asString(v);
      if (val) headers[k] = val;
    }
  }
  return {
    ok: true,
    request: {
      method: method.toUpperCase(),
      url,
      ...(Object.keys(headers).length > 0 ? { headers } : {}),
      ...(asString(payload.body) ? { body: asString(payload.body)! } : {}),
      ...(asNumber(payload.timeoutMs) ? { timeoutMs: asNumber(payload.timeoutMs)! } : {}),
    },
  };
}

function mapMcpStructuredToResult(
  protocol: TransportProtocol,
  structuredContent?: Record<string, unknown>,
): TransportExecutionResult {
  if (!structuredContent) {
    return {
      status: "blocked_runtime",
      protocol,
      durationMs: 1,
      reasonCode: "transport_wrapper_missing_response",
      errorMessage: "transport_execute returned no structuredContent payload",
    };
  }
  const statusRaw = asString(structuredContent.status);
  const status =
    statusRaw === "pass" || statusRaw === "fail_http" || statusRaw === "blocked_runtime" || statusRaw === "blocked_invalid"
      ? statusRaw
      : "blocked_runtime";
  const durationMs = Math.max(1, Math.round(asNumber(structuredContent.durationMs) ?? 1));
  const statusCode = asNumber(structuredContent.statusCode);
  const reasonCode = asString(structuredContent.reasonCode);
  const errorMessage = asString(structuredContent.errorMessage);
  const bodyTextRaw = asString(structuredContent.body);
  const bodyPreviewRaw = asString(structuredContent.bodyPreview);

  const headersRaw = structuredContent.headers;
  let headers: Record<string, string> | undefined;
  if (headersRaw && typeof headersRaw === "object" && !Array.isArray(headersRaw)) {
    headers = {};
    for (const [k, v] of Object.entries(headersRaw as Record<string, unknown>)) {
      const value = asString(v);
      if (value) headers[k] = value;
    }
  }

  return {
    status,
    protocol,
    durationMs,
    ...(typeof statusCode === "number" ? { statusCode } : {}),
    ...(reasonCode ? { reasonCode } : {}),
    ...(errorMessage ? { errorMessage } : {}),
    ...(bodyTextRaw ? { bodyText: bodyTextRaw } : {}),
    ...(bodyPreviewRaw ? { bodyPreview: toBodyPreview(bodyPreviewRaw) } : {}),
    ...(headers && Object.keys(headers).length > 0 ? { headers } : {}),
  };
}

export function createMcpWrappedTransportAdapter(mcpInvoke: McpToolInvoker): TransportAdapter {
  return {
    protocol: "http",
    async execute(input: TransportExecuteInput): Promise<TransportExecutionResult> {
      const parsed = validateHttpPayload(input.payload);
      if (!parsed.ok) {
        return {
          status: "blocked_invalid",
          protocol: "http",
          durationMs: 1,
          reasonCode: "http_payload_invalid",
          reasonMeta: parsed.reasonMeta,
          errorMessage: `http transport missing required field(s): ${String(parsed.reasonMeta.missingFields ?? []).replace(/,/g, ", ")}`,
        };
      }

      const out = await mcpInvoke({
        toolName: "transport_execute",
        input: {
          protocol: "http",
          request: parsed.request,
          options: { wrappedOnly: true },
        },
      });
      return mapMcpStructuredToResult("http", out.structuredContent);
    },
  };
}

export function createTransportRegistry(adapters: TransportAdapter[]): Map<TransportProtocol, TransportAdapter> {
  const registry = new Map<TransportProtocol, TransportAdapter>();
  for (const adapter of adapters) {
    registry.set(adapter.protocol, adapter);
  }
  return registry;
}

export async function executeTransportWithRegistry(args: {
  protocol: TransportProtocol;
  payload: Record<string, unknown>;
  registry: Map<TransportProtocol, TransportAdapter>;
}): Promise<TransportExecutionResult> {
  const adapter = args.registry.get(args.protocol);
  if (!adapter) {
    return {
      status: "blocked_invalid",
      protocol: args.protocol,
      durationMs: 1,
      reasonCode: "transport_not_supported",
      errorMessage: `No transport adapter registered for protocol=${args.protocol}`,
    };
  }
  return adapter.execute({ protocol: args.protocol, payload: args.payload });
}

