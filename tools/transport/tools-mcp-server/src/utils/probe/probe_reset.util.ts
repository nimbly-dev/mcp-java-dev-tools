import { fetchJson } from "@/lib/http";
import { clampInt, DEFAULT_PROBE_TIMEOUT_MS, HARD_MAX_PROBE_TIMEOUT_MS } from "@/lib/safety";
import type { ToolTextResponse } from "@tools-contracts/output";
import { deriveNextActionCode, normalizeReasonMeta } from "@/utils/failure_diagnostics.util";
import { joinUrl, probeUnreachableMessage } from "@/utils/probe.util";
import { formatProbeOutput } from "@/utils/probe/output.util";
import { isLineKey, resolveProbeKey } from "@/utils/probe/key.util";
import {
  buildBatchResponse,
  buildLineKeyRequiredResponse,
  buildTextResponse,
} from "@/utils/probe/response_builders.util";
import { GUIDANCE_RUNTIME_NOT_ALIGNED, LAST_RESET_EPOCH_BY_KEY } from "@/utils/probe/constants.util";
import { invalidLineTargetProbeHitMessage, readLineValidation } from "@/utils/probe/status_normalize.util";
import {
  normalizeOptionalString,
  normalizeOptionalStringArray,
  validateSelectorCount,
} from "@/utils/probe/selector_batch.util";

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

async function probeResetSingle(args: {
  key: string;
  lineHint?: number;
  baseUrl: string;
  resetPath: string;
  timeoutMs?: number;
}): Promise<ToolTextResponse> {
  const timeoutMs = clampInt(args.timeoutMs ?? DEFAULT_PROBE_TIMEOUT_MS, 1_000, HARD_MAX_PROBE_TIMEOUT_MS);
  const resolvedKey = resolveProbeKey(args.key, args.lineHint);
  const url = joinUrl(args.baseUrl, args.resetPath);

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
      httpRequest: `POST ${url}`,
      requestMethod: "POST",
      requestUrl: url,
      requestHeaders: { "content-type": "application/json" },
      requestBody: { key: resolvedKey },
      result: {
        reset: false,
        reason: reasonCode,
        reasonCode,
        nextActionCode: deriveNextActionCode(reasonCode),
        reasonMeta: normalizeReasonMeta({ failedStep: "input_validation" }),
      },
      runNotes: "probe_reset strict line mode",
    });
  }

  let res;
  try {
    res = await fetchJson(url, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ key: resolvedKey }),
      timeoutMs,
    });
  } catch (err) {
    throw new Error(probeUnreachableMessage(url, err));
  }

  const structuredContent: Record<string, unknown> = {
    request: buildSelectorRequest({
      key: args.key,
      resolvedKey,
      ...(typeof args.lineHint === "number" ? { lineHint: args.lineHint } : {}),
      url,
      timeoutMs,
    }),
    response: { status: res.status, json: res.json },
  };
  const json = res.json as Record<string, unknown> | null;
  const lineValidation = readLineValidation(json);
  const isOk = res.status >= 200 && res.status < 300;
  const semanticOk = isOk && !lineValidation.invalidLineTarget;

  if (semanticOk) {
    LAST_RESET_EPOCH_BY_KEY.set(resolvedKey, Date.now());
  }
  if (lineValidation.invalidLineTarget) {
    const reasonCode = "invalid_line_target";
    structuredContent.result = {
      reason: reasonCode,
      reasonCode,
      actionCode: GUIDANCE_RUNTIME_NOT_ALIGNED.actionCode,
      nextActionCode: GUIDANCE_RUNTIME_NOT_ALIGNED.nextActionCode,
      nextAction: GUIDANCE_RUNTIME_NOT_ALIGNED.nextAction,
      reasonMeta: normalizeReasonMeta({ failedStep: "line_validation" }),
    };
  }

  const text = formatProbeOutput({
    probeKey: resolvedKey,
    httpRequest: `POST ${url}`,
    requestMethod: "POST",
    requestUrl: url,
    requestHeaders: { "content-type": "application/json" },
    requestBody: { key: resolvedKey },
    executionHit: "not_applicable",
    apiOutcome: semanticOk ? "ok" : "error",
    reproStatus: lineValidation.invalidLineTarget ? "invalid_line_target" : isOk ? "reset_done" : "reset_failed",
    probeHit: lineValidation.invalidLineTarget
      ? `${invalidLineTargetProbeHitMessage(0)}; counter reset requested`
      : "counter reset requested",
    ...(lineValidation.invalidLineTarget
      ? {
          actionCode: GUIDANCE_RUNTIME_NOT_ALIGNED.actionCode,
          nextAction: GUIDANCE_RUNTIME_NOT_ALIGNED.nextAction,
        }
      : {}),
    httpCode: res.status,
    httpResponse: res.json ?? res.text,
    runDuration: "Not measured",
    runNotes: lineValidation.invalidLineTarget
      ? "probe_reset executed with invalid line target"
      : "probe_reset executed",
  });

  return buildTextResponse(structuredContent, text);
}

async function probeResetBatch(args: {
  keys?: string[];
  className?: string;
  baseUrl: string;
  resetPath: string;
  timeoutMs?: number;
}): Promise<ToolTextResponse> {
  const timeoutMs = clampInt(args.timeoutMs ?? DEFAULT_PROBE_TIMEOUT_MS, 1_000, HARD_MAX_PROBE_TIMEOUT_MS);
  const url = joinUrl(args.baseUrl, args.resetPath);
  const keys = normalizeOptionalStringArray(args.keys);
  const className = normalizeOptionalString(args.className);
  const requestedKeys = keys ? [...keys] : undefined;
  let requestedLineKeys: string[] = [];

  let requestBody: Record<string, unknown>;
  const localByKey = new Map<string, Record<string, unknown> & { apiOutcome: string }>();

  if (keys) {
    const lineKeys: string[] = [];
    for (const key of keys) {
      if (!isLineKey(key)) {
        localByKey.set(key, {
          key,
          executionHit: "not_applicable",
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
    requestedLineKeys = [...lineKeys];
    requestBody = { keys: lineKeys };
  } else if (className) {
    requestBody = { className };
  } else {
    requestBody = { keys: [] };
  }

  let res;
  try {
    res = await fetchJson(url, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(requestBody),
      timeoutMs,
    });
  } catch (err) {
    throw new Error(probeUnreachableMessage(url, err));
  }

  const json = res.json as Record<string, unknown> | null;
  const rawResults = Array.isArray(json?.results) ? (json.results as Array<Record<string, unknown>>) : [];
  const remoteByKey = new Map<string, Record<string, unknown>>();
  for (const row of rawResults) {
    if (typeof row?.key !== "string") continue;
    remoteByKey.set(row.key, row);
  }
  const remoteResults: Array<Record<string, unknown> & { apiOutcome: string }> = [];
  for (const row of rawResults) {
    const key = typeof row?.key === "string" ? row.key : undefined;
    if (!key) continue;
    const lineValidation = readLineValidation(row);
    const rowOk = typeof row.ok === "boolean" ? row.ok : res.status >= 200 && res.status < 300;
    const rowSemanticOk = rowOk && !lineValidation.invalidLineTarget;
    if (rowSemanticOk) {
      LAST_RESET_EPOCH_BY_KEY.set(key, Date.now());
    }
    remoteResults.push({
      key,
      executionHit: "not_applicable",
      apiOutcome: rowSemanticOk ? "ok" : "error",
      reproStatus: lineValidation.invalidLineTarget ? "invalid_line_target" : rowOk ? "reset_done" : "reset_failed",
      probeHit: lineValidation.invalidLineTarget
        ? `${invalidLineTargetProbeHitMessage(0)}; counter reset requested`
        : "counter reset requested",
      ...(lineValidation.invalidLineTarget
        ? {
            reasonCode: "invalid_line_target",
            actionCode: GUIDANCE_RUNTIME_NOT_ALIGNED.actionCode,
            nextActionCode: GUIDANCE_RUNTIME_NOT_ALIGNED.nextActionCode,
            nextAction: GUIDANCE_RUNTIME_NOT_ALIGNED.nextAction,
            reasonMeta: normalizeReasonMeta({ failedStep: "line_validation" }),
          }
        : {}),
      httpCode: res.status,
    });
  }
  for (const key of requestedLineKeys) {
    if (remoteByKey.has(key)) continue;
    remoteResults.push({
      key,
      executionHit: "not_applicable",
      apiOutcome: "error",
      reproStatus: "reset_failed",
      reasonCode: "reset_failed",
      nextActionCode: deriveNextActionCode("reset_failed"),
      probeHit: "missing batch reset row for key",
      httpCode: res.status,
    });
  }
  const orderedResults: Array<Record<string, unknown> & { apiOutcome: string }> = [];
  if (requestedKeys) {
    const remoteResultByKey = new Map<string, Record<string, unknown> & { apiOutcome: string }>();
    for (const row of remoteResults) {
      if (typeof row.key !== "string") continue;
      if (!remoteResultByKey.has(row.key)) remoteResultByKey.set(row.key, row);
    }
    for (const key of requestedKeys) {
      if (localByKey.has(key)) orderedResults.push(localByKey.get(key)!);
      const remote = remoteResultByKey.get(key);
      if (remote) orderedResults.push(remote);
    }
  } else {
    orderedResults.push(...remoteResults);
  }

  return buildBatchResponse({
    operation: "reset",
    request: { ...(keys ? { keys } : {}), ...(className ? { className } : {}), url, timeoutMs },
    results: orderedResults,
    response: {
      status: res.status,
      ...(typeof json?.reason === "string" ? { reason: json.reason } : {}),
      ...(typeof json?.selector === "string" ? { selector: json.selector } : {}),
      ...(typeof json?.className === "string" ? { className: json.className } : {}),
    },
  });
}

export async function probeReset(args: {
  key?: string;
  keys?: string[];
  className?: string;
  lineHint?: number;
  baseUrl: string;
  resetPath: string;
  timeoutMs?: number;
}): Promise<ToolTextResponse> {
  const key = normalizeOptionalString(args.key);
  const keys = normalizeOptionalStringArray(args.keys);
  const className = normalizeOptionalString(args.className);
  validateSelectorCount("probe_reset", [
    { enabled: typeof key === "string", name: "key" },
    { enabled: Array.isArray(keys), name: "keys" },
    { enabled: typeof className === "string", name: "className" },
  ]);
  if (keys || className) {
    if (typeof args.lineHint === "number") {
      throw new Error("probe_reset does not allow lineHint with keys[] or className. Use explicit line keys.");
    }
    const batchArgs: Parameters<typeof probeResetBatch>[0] = {
      ...(keys ? { keys } : {}),
      ...(className ? { className } : {}),
      baseUrl: args.baseUrl,
      resetPath: args.resetPath,
    };
    if (typeof args.timeoutMs === "number") batchArgs.timeoutMs = args.timeoutMs;
    return probeResetBatch(batchArgs);
  }
  const singleArgs: Parameters<typeof probeResetSingle>[0] = {
    key: key!,
    baseUrl: args.baseUrl,
    resetPath: args.resetPath,
  };
  if (typeof args.lineHint === "number") singleArgs.lineHint = args.lineHint;
  if (typeof args.timeoutMs === "number") singleArgs.timeoutMs = args.timeoutMs;
  return probeResetSingle(singleArgs);
}
