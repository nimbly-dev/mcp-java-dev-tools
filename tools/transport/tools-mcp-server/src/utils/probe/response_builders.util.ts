import { formatProbeOutput } from "@/utils/probe/output.util";
import type { ToolTextResponse } from "@tools-contracts/output";

export function buildTextResponse(
  structuredContent: Record<string, unknown>,
  text: string,
): ToolTextResponse {
  return { content: [{ type: "text", text }], structuredContent };
}

export function buildLineKeyRequiredResponse(args: {
  request: Record<string, unknown>;
  targetPath: string;
  httpRequest: string;
  requestMethod: string;
  requestUrl: string;
  requestHeaders?: Record<string, string>;
  requestBody?: unknown;
  result: Record<string, unknown>;
  runNotes: string;
}): ToolTextResponse {
  const structuredContent: Record<string, unknown> = {
    request: args.request,
    result: args.result,
  };
  const text = formatProbeOutput({
    probeKey: args.targetPath,
    httpRequest: args.httpRequest,
    requestMethod: args.requestMethod,
    requestUrl: args.requestUrl,
    ...(args.requestHeaders ? { requestHeaders: args.requestHeaders } : {}),
    ...(typeof args.requestBody !== "undefined" ? { requestBody: args.requestBody } : {}),
    executionHit: "not_hit",
    apiOutcome: "error",
    reproStatus: "line_key_required",
    probeHit: "line probe key required (Class#method:<line>); method-only checks disabled",
    httpCode: 400,
    httpResponse: args.result,
    runDuration: "Not measured",
    runNotes: args.runNotes,
  });
  return buildTextResponse(structuredContent, text);
}

function buildBatchSummary(results: Array<{ apiOutcome: string }>): {
  total: number;
  ok: number;
  failed: number;
} {
  let ok = 0;
  for (const result of results) {
    if (result.apiOutcome === "ok") ok += 1;
  }
  return { total: results.length, ok, failed: results.length - ok };
}

type BatchResultRow = Record<string, unknown> & { apiOutcome: string };

function summarizeBatchRequest(request: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  if (typeof request.url === "string") out.url = request.url;
  if (typeof request.timeoutMs === "number") out.timeoutMs = request.timeoutMs;
  if (Array.isArray(request.keys)) out.keyCount = request.keys.length;
  if (typeof request.className === "string") out.className = request.className;
  return out;
}

function summarizeBatchResults(results: BatchResultRow[]): Array<Record<string, unknown>> {
  const failed = results.filter((row) => row.apiOutcome !== "ok");
  return failed.slice(0, 10).map((row) => {
    const summary: Record<string, unknown> = {};
    if (typeof row.key === "string") summary.key = row.key;
    summary.apiOutcome = row.apiOutcome;
    if (typeof row.reproStatus === "string") summary.reproStatus = row.reproStatus;
    if (typeof row.reasonCode === "string") summary.reasonCode = row.reasonCode;
    if (typeof row.actionCode === "string") summary.actionCode = row.actionCode;
    if (typeof row.nextActionCode === "string") summary.nextActionCode = row.nextActionCode;
    if (typeof row.nextAction === "string") summary.nextAction = row.nextAction;
    return summary;
  });
}

export function buildBatchResponse(args: {
  operation: "status" | "reset";
  request: Record<string, unknown>;
  results: Array<BatchResultRow>;
  response?: unknown;
}): ToolTextResponse {
  const summary = buildBatchSummary(args.results);
  const payload = {
    mode: "probe_batch",
    operation: args.operation,
    request: args.request,
    summary,
    results: args.results,
    ...(typeof args.response !== "undefined" ? { response: args.response } : {}),
  };
  const compactTextPayload = {
    mode: "probe_batch",
    operation: args.operation,
    request: summarizeBatchRequest(args.request),
    summary,
    failures: summarizeBatchResults(args.results),
    notes: "Use structuredContent.results for full per-key payload.",
  };
  return buildTextResponse(payload, JSON.stringify(compactTextPayload, null, 2));
}
