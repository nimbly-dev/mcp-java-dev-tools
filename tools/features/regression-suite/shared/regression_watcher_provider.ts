import type { PlanWatcher } from "../../../spec/regression-execution-plan-spec/src/models/regression_execution_plan_spec.model";
import type {
  TransportExecutionResult,
  TransportProtocol,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_transport.model";
import { deepResolvePlaceholderValue } from "../../../spec/regression-execution-plan-spec/src/placeholder_resolution.util";
import { buildHttpPayload } from "../shared/regression_http_payload";

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function asString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
}

function asBodyFormat(value: unknown): WatcherResponseBodyFormat | undefined {
  return value === "auto" || value === "json" || value === "text" ? value : undefined;
}

function tryParseJson(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    return undefined;
  }
}

export type WatcherResponseBodyFormat = "auto" | "json" | "text";

export type WatcherProviderExecution = {
  providerType: string;
  protocol: TransportProtocol;
  payload: Record<string, unknown>;
  responseBodyFormat: WatcherResponseBodyFormat;
};

export type WatcherProviderResultEnvelope = {
  status: "pass" | "fail";
  provider: {
    type: string;
    protocol: TransportProtocol;
    responseBodyFormat: WatcherResponseBodyFormat;
  };
  response: {
    statusCode: number;
    body: string;
    bodyFormat: "text" | "json";
    headers?: Record<string, string>;
    bodyJson?: unknown;
  };
  transport: {
    status: TransportExecutionResult["status"];
    durationMs: number;
    reasonCode: string | null;
  };
};

export type WatcherObservationSummary = {
  status: "pass" | "fail";
  provider: {
    type: string;
    protocol: TransportProtocol;
    responseBodyFormat: WatcherResponseBodyFormat;
  };
  response: {
    statusCode: number;
    bodyFormat: "text" | "json";
    bodyBytes: number;
    headerNames?: string[];
    hasBodyJson: boolean;
  };
  transport: {
    status: TransportExecutionResult["status"];
    durationMs: number;
    reasonCode: string | null;
  };
};

export function resolveWatcherProviderExecution(args: {
  watcher: PlanWatcher;
  context: Record<string, unknown>;
  timeoutMs?: number;
}):
  | { ok: true; execution: WatcherProviderExecution }
  | { ok: false; reasonCode: string; reasonMeta?: Record<string, unknown> } {
  const providerType = asString(args.watcher.provider?.type);
  if (!providerType) {
    return { ok: false, reasonCode: "watcher_runtime_configuration_invalid" };
  }

  if (providerType !== "http") {
    return {
      ok: false,
      reasonCode: "watcher_provider_not_supported",
      reasonMeta: { providerType },
    };
  }

  const providerTransport = asRecord(args.watcher.provider?.transport);
  if (!providerTransport) {
    return {
      ok: false,
      reasonCode: "watcher_runtime_configuration_invalid",
      reasonMeta: { providerType, cause: "provider_transport_missing" },
    };
  }

  const providerConfig = asRecord(args.watcher.provider?.config);
  const responseConfig = providerConfig ? asRecord(providerConfig.response) : null;
  const responseBodyFormat = asBodyFormat(responseConfig?.bodyFormat) ?? "auto";
  if (providerConfig && responseConfig === null && typeof providerConfig.response !== "undefined") {
    return {
      ok: false,
      reasonCode: "watcher_runtime_configuration_invalid",
      reasonMeta: { providerType, cause: "provider_response_config_invalid" },
    };
  }
  if (
    providerConfig &&
    responseConfig &&
    typeof responseConfig.bodyFormat !== "undefined" &&
    !asBodyFormat(responseConfig.bodyFormat)
  ) {
    return {
      ok: false,
      reasonCode: "watcher_runtime_configuration_invalid",
      reasonMeta: {
        providerType,
        cause: "provider_response_body_format_invalid",
        supportedBodyFormats: ["auto", "json", "text"],
      },
    };
  }

  const resolvedTransport = deepResolvePlaceholderValue(providerTransport, args.context);
  const normalizedTransport = asRecord(resolvedTransport);
  if (!normalizedTransport) {
    return { ok: false, reasonCode: "watcher_runtime_configuration_invalid" };
  }

  const candidatePayload =
    asRecord(normalizedTransport.http) ??
    asRecord(normalizedTransport.request) ??
    normalizedTransport;
  const payload = buildHttpPayload({
    resolvedTransport: { http: candidatePayload },
    context: args.context,
  });
  const inheritedTimeoutMs =
    typeof payload.timeoutMs === "number" && Number.isFinite(payload.timeoutMs) && payload.timeoutMs > 0
      ? Math.floor(payload.timeoutMs)
      : typeof args.context["runtime.requestTimeoutMs"] === "number" &&
          Number.isFinite(args.context["runtime.requestTimeoutMs"]) &&
          args.context["runtime.requestTimeoutMs"] > 0
        ? Math.floor(args.context["runtime.requestTimeoutMs"] as number)
        : undefined;
  const boundedTimeoutMs =
    typeof args.timeoutMs === "number" && args.timeoutMs > 0
      ? typeof inheritedTimeoutMs === "number"
        ? Math.min(inheritedTimeoutMs, args.timeoutMs)
        : args.timeoutMs
      : inheritedTimeoutMs;
  if (typeof boundedTimeoutMs === "number" && boundedTimeoutMs > 0) {
    payload.timeoutMs = boundedTimeoutMs;
  }

  return {
    ok: true,
    execution: {
      providerType,
      protocol: "http",
      payload,
      responseBodyFormat,
    },
  };
}

export function summarizeWatcherObservation(
  envelope: WatcherProviderResultEnvelope,
): WatcherObservationSummary {
  const headerNames = envelope.response.headers ? Object.keys(envelope.response.headers).sort() : [];
  return {
    status: envelope.status,
    provider: envelope.provider,
    response: {
      statusCode: envelope.response.statusCode,
      bodyFormat: envelope.response.bodyFormat,
      bodyBytes: Buffer.byteLength(envelope.response.body, "utf8"),
      ...(headerNames.length > 0 ? { headerNames } : {}),
      hasBodyJson: typeof envelope.response.bodyJson !== "undefined",
    },
    transport: envelope.transport,
  };
}

export function normalizeWatcherProviderResult(args: {
  execution: WatcherProviderExecution;
  transport: TransportExecutionResult;
}):
  | { ok: true; envelope: WatcherProviderResultEnvelope }
  | { ok: false; reasonCode: "watcher_response_normalization_failed"; reasonMeta: Record<string, unknown> } {
  const responseBody = args.transport.bodyText ?? args.transport.bodyPreview ?? "";
  const parsedBody = tryParseJson(responseBody);
  const expectsJson = args.execution.responseBodyFormat === "json";
  const parsedJson = expectsJson || args.execution.responseBodyFormat === "auto" ? parsedBody : undefined;

  if (expectsJson && typeof parsedJson === "undefined") {
    return {
      ok: false,
      reasonCode: "watcher_response_normalization_failed",
      reasonMeta: {
        providerType: args.execution.providerType,
        protocol: args.execution.protocol,
        expectedBodyFormat: args.execution.responseBodyFormat,
        cause: "response_body_json_invalid",
      },
    };
  }

  return {
    ok: true,
    envelope: {
      status: args.transport.status === "pass" ? "pass" : "fail",
      provider: {
        type: args.execution.providerType,
        protocol: args.execution.protocol,
        responseBodyFormat: args.execution.responseBodyFormat,
      },
      response: {
        statusCode: args.transport.statusCode ?? 0,
        body: responseBody,
        bodyFormat: typeof parsedJson === "undefined" ? "text" : "json",
        ...(args.transport.headers ? { headers: args.transport.headers } : {}),
        ...(typeof parsedJson === "undefined" ? {} : { bodyJson: parsedJson }),
      },
      transport: {
        status: args.transport.status,
        durationMs: args.transport.durationMs,
        reasonCode: args.transport.reasonCode ?? null,
      },
    },
  };
}
