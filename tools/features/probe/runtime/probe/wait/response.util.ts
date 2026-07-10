import type { ToolTextResponse } from "@tools-contracts/output";
import { classifyExecutionHitStrictLine, classifyReproStatusStrictLine } from "../key.util";
import { formatProbeOutput } from "../output.util";
import { buildTextResponse } from "../response_builders.util";
import { compactStatusPayload } from "../compact_payload.util";
import {
  GUIDANCE_LINE_NOT_EXECUTED_IN_WINDOW,
  GUIDANCE_PROBE_CONNECTIVITY_ISSUE,
  GUIDANCE_RUNTIME_NOT_ALIGNED,
} from "../constants.util";
import { invalidLineTargetProbeHitMessage } from "../status_normalize.util";
import type { ProbeStatusUnreachableDetails } from "./poll_status.util";

type WaitRequestArgs = {
  key: string;
  resolvedKey: string;
  timeoutMs: number;
  pollIntervalMs: number;
  maxRetries: number;
  unreachableRetryEnabled: boolean;
  unreachableMaxRetries: number;
  attempt: number;
  waitStartEpoch: number;
  triggerWindowStartEpoch: number;
};

type WaitStage = "baseline_status_check" | "poll_status_check";

function compactWaitStatus(status: Record<string, unknown> | null): Record<string, unknown> | null {
  if (!status) return null;
  const response =
    typeof status.response === "object" && status.response !== null
      ? (status.response as Record<string, unknown>)
      : null;
  const fromResponse =
    response && typeof response.json === "object" && response.json !== null
      ? (response.json as Record<string, unknown>)
      : null;
  return compactStatusPayload(fromResponse ?? status);
}

function buildWaitRequest(args: WaitRequestArgs, options?: { includeUnreachable?: boolean; stage?: WaitStage }) {
  const triggerLeadMs = Math.max(0, args.waitStartEpoch - args.triggerWindowStartEpoch);
  const keyFields =
    args.key === args.resolvedKey
      ? { key: args.resolvedKey }
      : { key: args.key, resolvedKey: args.resolvedKey };
  const request: Record<string, unknown> = {
    ...keyFields,
    timeoutMs: args.timeoutMs,
    pollIntervalMs: args.pollIntervalMs,
    maxRetries: args.maxRetries,
    attempt: args.attempt,
    waitStartEpoch: args.waitStartEpoch,
    triggerWindowStartEpoch: args.triggerWindowStartEpoch,
    triggerLeadMs,
  };
  if (options?.includeUnreachable) {
    request.unreachableRetryEnabled = args.unreachableRetryEnabled;
    request.unreachableMaxRetries = args.unreachableMaxRetries;
  }
  if (options?.stage) request.stage = options.stage;
  return request;
}

export function buildServiceUnreachableResponse(args: {
  request: WaitRequestArgs;
  stage: WaitStage;
  details: ProbeStatusUnreachableDetails;
  baseline?: { hitCount: number; lastHitEpoch: number };
}): ToolTextResponse {
  const structuredContent: Record<string, unknown> = {
    request: buildWaitRequest(args.request, { includeUnreachable: true, stage: args.stage }),
    result: {
      hit: false,
      inline: false,
      reason: "service_unreachable",
      reasonCode: "service_unreachable",
      actionCode: GUIDANCE_PROBE_CONNECTIVITY_ISSUE.actionCode,
      nextActionCode: GUIDANCE_PROBE_CONNECTIVITY_ISSUE.nextActionCode,
      nextAction: GUIDANCE_PROBE_CONNECTIVITY_ISSUE.nextAction,
      reasonMeta: {
        failedStep: args.stage,
        unreachableAttempts: args.details.unreachableAttempts,
      },
      endpoint: args.details.endpoint,
      lastError: args.details.lastError,
      unreachableAttempts: args.details.unreachableAttempts,
      unreachableMaxRetries: args.details.unreachableMaxRetries,
      unreachableRetryEnabled: args.details.unreachableRetryEnabled,
    },
  };
  if (args.baseline) {
    structuredContent.baseline = {
      hitCount: args.baseline.hitCount,
      lastHitEpoch: args.baseline.lastHitEpoch,
    };
  }
  const text = formatProbeOutput({
    probeKey: args.request.resolvedKey,
    httpRequest: `POLL ${args.details.endpoint}`,
    requestMethod: "POLL",
    requestUrl: args.details.endpoint,
    requestBody: {
      key: args.request.resolvedKey,
      timeoutMs: args.request.timeoutMs,
      pollIntervalMs: args.request.pollIntervalMs,
      maxRetries: args.request.maxRetries,
      unreachableRetryEnabled: args.request.unreachableRetryEnabled,
      unreachableMaxRetries: args.request.unreachableMaxRetries,
    },
    executionHit: "not_hit",
    apiOutcome: "error",
    reproStatus: "probe_unreachable",
    probeHit:
      `probe endpoint unreachable; attempts=${args.details.unreachableAttempts}/` +
      `${args.details.unreachableMaxRetries}`,
    httpCode: 503,
    httpResponse: structuredContent.result,
    actionCode: GUIDANCE_PROBE_CONNECTIVITY_ISSUE.actionCode,
    nextAction: GUIDANCE_PROBE_CONNECTIVITY_ISSUE.nextAction,
    runDuration: `${Date.now() - args.request.waitStartEpoch}ms`,
    runNotes: `probe_wait_for_hit service unreachable during ${args.stage}`,
  });
  return buildTextResponse(structuredContent, text);
}

export function buildInvalidLineTargetResponse(args: {
  request: WaitRequestArgs;
  pollUrl: string;
  baselineHitCount: number;
  lineValidation: string;
  lastStatus: Record<string, unknown> | null;
  runNotes: string;
  baseline?: { hitCount: number; lastHitEpoch: number };
}): ToolTextResponse {
  const structuredContent: Record<string, unknown> = {
    request: buildWaitRequest(args.request, { includeUnreachable: true }),
    result: {
      hit: false,
      inline: false,
      reason: "invalid_line_target",
      reasonCode: "invalid_line_target",
      actionCode: GUIDANCE_RUNTIME_NOT_ALIGNED.actionCode,
      nextActionCode: GUIDANCE_RUNTIME_NOT_ALIGNED.nextActionCode,
      nextAction: GUIDANCE_RUNTIME_NOT_ALIGNED.nextAction,
      reasonMeta: {
        failedStep: "line_validation",
        lineValidation: args.lineValidation,
      },
      lineValidation: args.lineValidation,
      lastStatus: compactWaitStatus(args.lastStatus),
    },
  };
  if (args.baseline) {
    structuredContent.baseline = {
      hitCount: args.baseline.hitCount,
      lastHitEpoch: args.baseline.lastHitEpoch,
    };
  }
  const text = formatProbeOutput({
    probeKey: args.request.resolvedKey,
    httpRequest: `POLL ${args.pollUrl} (maxRetries=${args.request.maxRetries}, pollMs=${args.request.pollIntervalMs})`,
    requestMethod: "POLL",
    requestUrl: args.pollUrl,
    requestBody: {
      key: args.request.resolvedKey,
      maxRetries: args.request.maxRetries,
      pollIntervalMs: args.request.pollIntervalMs,
      timeoutMs: args.request.timeoutMs,
    },
    executionHit: "not_hit",
    apiOutcome: "error",
    reproStatus: "invalid_line_target",
    probeHit: invalidLineTargetProbeHitMessage(args.baselineHitCount),
    actionCode: GUIDANCE_RUNTIME_NOT_ALIGNED.actionCode,
    nextAction: GUIDANCE_RUNTIME_NOT_ALIGNED.nextAction,
    httpCode: 422,
    httpResponse: structuredContent.result,
    runtimeMode: typeof args.lastStatus?.mode === "string" ? args.lastStatus.mode : undefined,
    runDuration: `${Date.now() - args.request.waitStartEpoch}ms`,
    runNotes: args.runNotes,
  });
  return buildTextResponse(structuredContent, text);
}

export function buildBaselineInlineHitResponse(args: {
  request: WaitRequestArgs;
  pollUrl: string;
  baselineHitCount: number;
  baselineLastHitEpoch: number;
  lastStatus: Record<string, unknown>;
}): ToolTextResponse {
  const structuredContent: Record<string, unknown> = {
    request: buildWaitRequest(args.request),
    baseline: { hitCount: args.baselineHitCount, lastHitEpoch: args.baselineLastHitEpoch },
    result: {
      hit: true,
      inline: true,
      source: "already_hit_since_inline_start",
      hitCount: args.baselineHitCount,
      hitDelta: 0,
      lastStatus: compactWaitStatus(args.lastStatus),
    },
  };
  const text = formatProbeOutput({
    probeKey: args.request.resolvedKey,
    httpRequest: `POLL ${args.pollUrl} (maxRetries=${args.request.maxRetries}, pollMs=${args.request.pollIntervalMs})`,
    requestMethod: "POLL",
    requestUrl: args.pollUrl,
    requestBody: {
      key: args.request.resolvedKey,
      maxRetries: args.request.maxRetries,
      pollIntervalMs: args.request.pollIntervalMs,
      timeoutMs: args.request.timeoutMs,
    },
    executionHit: classifyExecutionHitStrictLine(args.request.resolvedKey, true),
    apiOutcome: "ok",
    reproStatus: classifyReproStatusStrictLine(args.request.resolvedKey, true),
    probeHit: `inline line hit confirmed; hitCount=${args.baselineHitCount}`,
    httpCode: 200,
    httpResponse: structuredContent.result,
    runtimeMode: typeof args.lastStatus.mode === "string" ? args.lastStatus.mode : undefined,
    runDuration: `${Date.now() - args.request.waitStartEpoch}ms`,
    runNotes: "probe_wait_for_hit detected baseline inline hit",
  });
  return buildTextResponse(structuredContent, text);
}

export function buildPolledInlineHitResponse(args: {
  request: WaitRequestArgs;
  pollUrl: string;
  baselineHitCount: number;
  baselineLastHitEpoch: number;
  hitCount: number;
  hitDelta: number;
  lastStatus: Record<string, unknown>;
}): ToolTextResponse {
  const structuredContent: Record<string, unknown> = {
    request: buildWaitRequest(args.request, { includeUnreachable: true }),
    baseline: { hitCount: args.baselineHitCount, lastHitEpoch: args.baselineLastHitEpoch },
    result: {
      hit: true,
      inline: true,
      hitCount: args.hitCount,
      hitDelta: args.hitDelta,
      lastStatus: compactWaitStatus(args.lastStatus),
    },
  };
  const text = formatProbeOutput({
    probeKey: args.request.resolvedKey,
    httpRequest: `POLL ${args.pollUrl} (maxRetries=${args.request.maxRetries}, pollMs=${args.request.pollIntervalMs})`,
    requestMethod: "POLL",
    requestUrl: args.pollUrl,
    requestBody: {
      key: args.request.resolvedKey,
      maxRetries: args.request.maxRetries,
      pollIntervalMs: args.request.pollIntervalMs,
      timeoutMs: args.request.timeoutMs,
    },
    executionHit: classifyExecutionHitStrictLine(args.request.resolvedKey, true),
    apiOutcome: "ok",
    reproStatus: classifyReproStatusStrictLine(args.request.resolvedKey, true),
    probeHit: `inline line hit confirmed; hitCount=${args.hitCount}, hitDelta=${args.hitDelta}`,
    httpCode: 200,
    httpResponse: structuredContent.result,
    runtimeMode: typeof args.lastStatus.mode === "string" ? args.lastStatus.mode : undefined,
    runDuration: `${Date.now() - args.request.waitStartEpoch}ms`,
    runNotes: "probe_wait_for_hit detected inline hit during poll",
  });
  return buildTextResponse(structuredContent, text);
}

export function buildTimeoutNoInlineHitResponse(args: {
  key: string;
  resolvedKey: string;
  timeoutMs: number;
  pollIntervalMs: number;
  maxRetries: number;
  unreachableRetryEnabled: boolean;
  unreachableMaxRetries: number;
  pollUrl: string;
  last: Record<string, unknown> | null;
  staleCandidate: Record<string, unknown> | undefined;
}): ToolTextResponse {
  const keyFields =
    args.key === args.resolvedKey
      ? { key: args.resolvedKey }
      : { key: args.key, resolvedKey: args.resolvedKey };
  const structuredContent: Record<string, unknown> = {
    request: {
      ...keyFields,
      timeoutMs: args.timeoutMs,
      pollIntervalMs: args.pollIntervalMs,
      maxRetries: args.maxRetries,
      unreachableRetryEnabled: args.unreachableRetryEnabled,
      unreachableMaxRetries: args.unreachableMaxRetries,
    },
    result: {
      hit: false,
      inline: false,
      reason: "timeout_no_inline_hit",
      reasonCode: "timeout_no_inline_hit",
      actionCode: GUIDANCE_LINE_NOT_EXECUTED_IN_WINDOW.actionCode,
      nextActionCode: GUIDANCE_LINE_NOT_EXECUTED_IN_WINDOW.nextActionCode,
      nextAction: GUIDANCE_LINE_NOT_EXECUTED_IN_WINDOW.nextAction,
      reasonMeta: {
        failedStep: "wait_poll",
        waitedMs: args.timeoutMs,
      },
      lastStatus: compactWaitStatus(args.last),
      staleCandidate: args.staleCandidate,
    },
  };
  const text = formatProbeOutput({
    probeKey: args.resolvedKey,
    httpRequest: `POLL ${args.pollUrl} (maxRetries=${args.maxRetries}, pollMs=${args.pollIntervalMs})`,
    requestMethod: "POLL",
    requestUrl: args.pollUrl,
    requestBody: { key: args.resolvedKey, maxRetries: args.maxRetries, pollIntervalMs: args.pollIntervalMs, timeoutMs: args.timeoutMs },
    executionHit: classifyExecutionHitStrictLine(args.resolvedKey, false),
    apiOutcome: "timeout",
    reproStatus: classifyReproStatusStrictLine(args.resolvedKey, false),
    probeHit: "no inline line hit observed",
    actionCode: GUIDANCE_LINE_NOT_EXECUTED_IN_WINDOW.actionCode,
    nextAction: GUIDANCE_LINE_NOT_EXECUTED_IN_WINDOW.nextAction,
    httpCode: 408,
    httpResponse: structuredContent.result,
    runtimeMode:
      typeof compactWaitStatus(args.last)?.mode === "string"
        ? (compactWaitStatus(args.last)?.mode as string)
        : undefined,
    runDuration: `${args.timeoutMs}ms x ${args.maxRetries}`,
    runNotes: "probe_wait_for_hit timeout",
  });
  return buildTextResponse(structuredContent, text);
}
