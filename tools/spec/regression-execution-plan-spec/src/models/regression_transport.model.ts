export type TransportProtocol = "http" | "grpc" | "kafka" | "custom";

export type TransportExecutionStatus = "pass" | "fail_http" | "blocked_runtime" | "blocked_invalid";

export type TransportExecutionResult = {
  status: TransportExecutionStatus;
  protocol: TransportProtocol;
  statusCode?: number;
  durationMs: number;
  headers?: Record<string, string>;
  bodyText?: string;
  bodyPreview?: string;
  reasonCode?: string;
  reasonMeta?: Record<string, unknown>;
  errorMessage?: string;
};

export type HttpTransportRequest = {
  method: string;
  url: string;
  headers?: Record<string, string>;
  body?: string;
  timeoutMs?: number;
};

export type TransportExecuteInput = {
  protocol: TransportProtocol;
  payload: Record<string, unknown>;
};

export type TransportAdapter = {
  protocol: TransportProtocol;
  execute(input: TransportExecuteInput): Promise<TransportExecutionResult>;
};

