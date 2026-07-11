import type {
  TransportExecutionResult,
  TransportProtocol,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_transport.model";

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
export type ResolvedWatcherWaitPolicy = {
  timeoutMs?: number;
  timeoutSource: "watcher_override" | "project_default" | "unresolved";
  retryMax?: number;
  retrySource: "watcher_override" | "project_default" | "unresolved";
};
