import { fetchJson } from "@tools-core/http";
import { clampInt, DEFAULT_PROBE_TIMEOUT_MS, HARD_MAX_PROBE_TIMEOUT_MS } from "@tools-core/safety";
import type { ToolTextResponse } from "@tools-contracts/output";
import { deriveNextActionCode, normalizeReasonMeta } from "@tools-core/failure_diagnostics";
import { joinUrl, probeUnreachableMessage } from "../probe.util";
import { classifyExecutionHitStrictLine, isLineKey, resolveProbeKey } from "./key.util";
import {
  buildBatchResponse,
  buildLineKeyRequiredResponse,
  buildTextResponse,
} from "./response_builders.util";
import { GUIDANCE_RUNTIME_NOT_ALIGNED } from "./constants.util";
import {
  invalidLineTargetProbeHitMessage,
  normalizeStatusBatchPayload,
  normalizeStatusBatchRow,
  normalizeStatusJson,
  readLineValidation,
} from "./status_normalize.util";
import {
  normalizeOptionalString,
  normalizeOptionalStringArray,
  validateSelectorCount,
} from "./selector_batch.util";
import { formatProbeOutput } from "./output.util";

function buildSelectorRequest(args: {
  key: string;
  resolvedKey: string;
  lineHint?: number;
  timeoutMs: number;
  url?: string;
}): Record<string, unknown> {
  const keyFields =
    args.key === args.resolvedKey
      ? { key: args.resolvedKey }
      : { key: args.key, resolvedKey: args.resolvedKey };
  return {
    ...keyFields,
    ...(typeof args.lineHint === "number" ? { lineHint: args.lineHint } : {}),
    ...(typeof args.url === "string" ? { url: args.url } : {}),
    timeoutMs: args.timeoutMs,
  };
}

async function probeStatusSingle(args: {
  key: string;
  lineHint?: number;
  baseUrl: string;
  statusPath: string;
  timeoutMs?: number;
}): Promise<ToolTextResponse> {
  const timeoutMs = clampInt(args.timeoutMs ?? DEFAULT_PROBE_TIMEOUT_MS, 1_000, HARD_MAX_PROBE_TIMEOUT_MS);
  const resolvedKey = resolveProbeKey(args.key, args.lineHint);
  const urlString = `${joinUrl(args.baseUrl, args.statusPath)}?key=${encodeURIComponent(resolvedKey)}`;

  if (!isLineKey(resolvedKey)) {
    const reasonCode = "line_key_required";
    return buildLineKeyRequiredResponse({
      request: buildSelectorRequest({
        key: args.key,
        resolvedKey,
        ...(typeof args.lineHint === "number" ? { lineHint: args.lineHint } : {}),
        timeoutMs,
      }),
      targetPath: resolvedKey,
      httpRequest: `GET ${urlString}`,
      requestMethod: "GET",
      requestUrl: urlString,
      result: {
        hit: false,
        reason: reasonCode,
        reasonCode,
        nextActionCode: deriveNextActionCode(reasonCode),
        reasonMeta: normalizeReasonMeta({ failedStep: "input_validation" }),
      },
      runNotes: "probe_get_status strict line mode",
    });
  }

  const url = new URL(joinUrl(args.baseUrl, args.statusPath));
  url.searchParams.set("key", resolvedKey);

  let res;
  try {
    res = await fetchJson(url.toString(), { method: "GET", timeoutMs });
  } catch (err) {
    throw new Error(probeUnreachableMessage(url.toString(), err));
  }
  const normalizedJson = normalizeStatusJson((res.json as Record<string, unknown> | null) ?? null);

  const structuredContent: Record<string, unknown> = {
    request: buildSelectorRequest({
      key: args.key,
      resolvedKey,
      ...(typeof args.lineHint === "number" ? { lineHint: args.lineHint } : {}),
      url: url.toString(),
      timeoutMs,
    }),
    response: { status: res.status, json: normalizedJson },
  };

  const json = normalizedJson;
  const hitCount = typeof json?.hitCount === "number" ? json.hitCount : 0;
  const lineValidation = readLineValidation(json);
  const reproStatus = lineValidation.invalidLineTarget ? "invalid_line_target" : "status_checked";
  const executionHit =
    lineValidation.invalidLineTarget ? "not_hit" : classifyExecutionHitStrictLine(resolvedKey, hitCount > 0);
  const probeHit = lineValidation.invalidLineTarget
    ? invalidLineTargetProbeHitMessage(hitCount)
    : json !== null
    ? `hitCount=${typeof json.hitCount === "number" ? json.hitCount : 0}, lastHitEpoch=${typeof json.lastHitEpoch === "number" ? json.lastHitEpoch : 0}`
      : "No JSON probe payload";
  const guidance = lineValidation.invalidLineTarget ? GUIDANCE_RUNTIME_NOT_ALIGNED : undefined;
  if (guidance) {
    const reasonCode = "invalid_line_target";
    structuredContent.result = {
      reason: reasonCode,
      reasonCode,
      actionCode: guidance.actionCode,
      nextActionCode: guidance.nextActionCode,
      nextAction: guidance.nextAction,
      reasonMeta: normalizeReasonMeta({ failedStep: "line_validation" }),
    };
  }

  const text = formatProbeOutput({
    probeKey: resolvedKey,
    httpRequest: `GET ${url.toString()}`,
    requestMethod: "GET",
    requestUrl: url.toString(),
    executionHit,
    apiOutcome: res.status >= 200 && res.status < 300 ? "ok" : "error",
    reproStatus,
    probeHit,
    ...(guidance ? { actionCode: guidance.actionCode, nextAction: guidance.nextAction } : {}),
    httpCode: res.status,
    httpResponse: normalizedJson ?? res.text,
    runtimeMode: typeof json?.mode === "string" ? json.mode : undefined,
    runDuration: "Not measured",
    runNotes: lineValidation.invalidLineTarget
      ? "probe_get_status executed with invalid line target"
      : "probe_get_status executed",
  });

  return buildTextResponse(structuredContent, text);
}

async function probeStatusBatch(args: {
  keys: string[];
  baseUrl: string;
  statusPath: string;
  timeoutMs?: number;
}): Promise<ToolTextResponse> {
  const timeoutMs = clampInt(args.timeoutMs ?? DEFAULT_PROBE_TIMEOUT_MS, 1_000, HARD_MAX_PROBE_TIMEOUT_MS);
  const url = joinUrl(args.baseUrl, args.statusPath);
  const keys = normalizeOptionalStringArray(args.keys) ?? [];

  const localByKey = new Map<string, Record<string, unknown> & { apiOutcome: string }>();
  const lineKeys: string[] = [];
  for (const key of keys) {
    if (!isLineKey(key)) {
      localByKey.set(key, {
        key,
        executionHit: "not_hit",
        apiOutcome: "error",
        reproStatus: "line_key_required",
        reasonCode: "line_key_required",
        nextActionCode: deriveNextActionCode("line_key_required"),
        reasonMeta: normalizeReasonMeta({ failedStep: "input_validation" }),
        probeHit: "line probe key required (Class#method:<line>); method-only checks disabled",
        httpCode: 400,
      });
      continue;
    }
    lineKeys.push(key);
  }

  let remoteResponse: { status: number; json: unknown; text: string | null } | undefined;
  const remoteByKey = new Map<string, Record<string, unknown>>();
  if (lineKeys.length > 0) {
    try {
      remoteResponse = await fetchJson(url, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ keys: lineKeys }),
        timeoutMs,
      });
    } catch (err) {
      throw new Error(probeUnreachableMessage(url, err));
    }
    const rawJson = normalizeStatusBatchPayload(remoteResponse.json) as Record<string, unknown> | null;
    const rawResults = Array.isArray(rawJson?.results) ? (rawJson.results as Array<Record<string, unknown>>) : [];
    for (const row of rawResults) {
      const normalizedRow = normalizeStatusBatchRow(row);
      if (typeof normalizedRow?.key !== "string") continue;
      remoteByKey.set(normalizedRow.key, normalizedRow);
    }
    remoteResponse = { ...remoteResponse, json: rawJson };
  }

  for (const key of lineKeys) {
    const row = remoteByKey.get(key);
    if (!row) {
      localByKey.set(key, {
        key,
        executionHit: "not_hit",
        apiOutcome: "error",
        reproStatus: "status_failed",
        reasonCode: "status_failed",
        nextActionCode: deriveNextActionCode("status_failed"),
        probeHit: "missing batch status row for key",
        httpCode: remoteResponse?.status ?? 500,
      });
      continue;
    }
    const hitCount = typeof row.hitCount === "number" ? row.hitCount : 0;
    const lastHitEpoch = typeof row.lastHitEpoch === "number" ? row.lastHitEpoch : undefined;
    const lineValidation = readLineValidation(row);
    const guidance = lineValidation.invalidLineTarget ? GUIDANCE_RUNTIME_NOT_ALIGNED : undefined;
    localByKey.set(key, {
      key,
      hitCount,
      ...(typeof lastHitEpoch === "number" ? { lastHitEpoch } : {}),
      ...(typeof lineValidation.lineResolvable === "boolean"
        ? { lineResolvable: lineValidation.lineResolvable }
        : {}),
      ...(typeof lineValidation.lineValidation === "string"
        ? { lineValidation: lineValidation.lineValidation }
        : {}),
      executionHit: lineValidation.invalidLineTarget ? "not_hit" : classifyExecutionHitStrictLine(key, hitCount > 0),
      apiOutcome:
        typeof row.ok === "boolean"
          ? row.ok
            ? "ok"
            : "error"
          : remoteResponse && remoteResponse.status >= 200 && remoteResponse.status < 300
            ? "ok"
            : "error",
      reproStatus: lineValidation.invalidLineTarget ? "invalid_line_target" : "status_checked",
      ...(lineValidation.invalidLineTarget
        ? {
            reasonCode: "invalid_line_target",
            nextActionCode: guidance?.nextActionCode ?? deriveNextActionCode("invalid_line_target"),
            reasonMeta: normalizeReasonMeta({ failedStep: "line_validation" }),
          }
        : {}),
      probeHit: lineValidation.invalidLineTarget
        ? invalidLineTargetProbeHitMessage(hitCount)
          : `hitCount=${hitCount}, lastHitEpoch=${typeof row.lastHitEpoch === "number" ? row.lastHitEpoch : 0}`,
      ...(guidance
        ? {
            actionCode: guidance.actionCode,
            nextActionCode: guidance.nextActionCode,
            nextAction: guidance.nextAction,
          }
        : {}),
      httpCode: remoteResponse?.status ?? 200,
      runtimeMode: typeof row.mode === "string" ? row.mode : undefined,
      ...(typeof row.capturePreview === "object" && row.capturePreview !== null
        ? { capturePreview: row.capturePreview }
        : {}),
    });
  }
  const orderedResults: Array<Record<string, unknown> & { apiOutcome: string }> = [];
  for (const key of keys) {
    const row = localByKey.get(key);
    if (!row) continue;
    orderedResults.push(row);
  }

  return buildBatchResponse({
    operation: "status",
    request: { keys, url, timeoutMs },
    results: orderedResults,
    response: remoteResponse ? { status: remoteResponse.status } : undefined,
  });
}

export async function probeStatus(args: {
  key?: string;
  keys?: string[];
  lineHint?: number;
  baseUrl: string;
  statusPath: string;
  timeoutMs?: number;
}): Promise<ToolTextResponse> {
  const key = normalizeOptionalString(args.key);
  const keys = normalizeOptionalStringArray(args.keys);
  validateSelectorCount("probe_get_status", [
    { enabled: typeof key === "string", name: "key" },
    { enabled: Array.isArray(keys), name: "keys" },
  ]);
  if (keys) {
    if (typeof args.lineHint === "number") {
      throw new Error("probe_get_status does not allow lineHint with keys[]. Use explicit line keys.");
    }
    const batchArgs: Parameters<typeof probeStatusBatch>[0] = {
      keys,
      baseUrl: args.baseUrl,
      statusPath: args.statusPath,
    };
    if (typeof args.timeoutMs === "number") batchArgs.timeoutMs = args.timeoutMs;
    return probeStatusBatch(batchArgs);
  }
  const singleArgs: Parameters<typeof probeStatusSingle>[0] = {
    key: key!,
    baseUrl: args.baseUrl,
    statusPath: args.statusPath,
  };
  if (typeof args.lineHint === "number") singleArgs.lineHint = args.lineHint;
  if (typeof args.timeoutMs === "number") singleArgs.timeoutMs = args.timeoutMs;
  return probeStatusSingle(singleArgs);
}
