import {
  clampInt,
  DEFAULT_PROBE_POLL_INTERVAL_MS,
  DEFAULT_PROBE_TIMEOUT_MS,
  DEFAULT_PROBE_WAIT_MAX_RETRIES,
  HARD_MAX_PROBE_POLL_INTERVAL_MS,
  HARD_MAX_PROBE_TIMEOUT_MS,
  HARD_MAX_PROBE_WAIT_MAX_RETRIES,
} from "@/lib/safety";
import type { ToolTextResponse } from "@/models/tool_response.model";
import { deriveNextActionCode, normalizeReasonMeta } from "@/utils/failure_diagnostics.util";
import { joinUrl, parseProbeSnapshot } from "@/utils/probe.util";
import { isLineKey, resolveProbeKey } from "@/utils/probe/key.util";
import { buildLineKeyRequiredResponse } from "@/utils/probe/response_builders.util";
import {
  DEFAULT_PROBE_WAIT_UNREACHABLE_MAX_RETRIES,
  HARD_MAX_PROBE_WAIT_UNREACHABLE_MAX_RETRIES,
} from "@/utils/probe/constants.util";
import { readLineValidation } from "@/utils/probe/status_normalize.util";
import { sleep } from "@/utils/probe/wait_policy.util";
import { probeStatusWithUnreachablePolicy } from "@/utils/probe/wait/poll_status.util";
import {
  buildBaselineInlineHitResponse,
  buildInvalidLineTargetResponse,
  buildPolledInlineHitResponse,
  buildServiceUnreachableResponse,
  buildTimeoutNoInlineHitResponse,
} from "@/utils/probe/wait/response.util";
import { hasBaselineInlineHit, isInlineByTime, resolveProbeWaitWindow } from "@/utils/probe/wait/window.util";

function buildSelectorRequest(args: {
  key: string;
  resolvedKey: string;
  timeoutMs: number;
  pollIntervalMs: number;
  maxRetries: number;
  unreachableRetryEnabled: boolean;
  unreachableMaxRetries: number;
}): Record<string, unknown> {
  const keyFields =
    args.key === args.resolvedKey
      ? { key: args.resolvedKey }
      : { key: args.key, resolvedKey: args.resolvedKey };
  return {
    ...keyFields,
    timeoutMs: args.timeoutMs,
    pollIntervalMs: args.pollIntervalMs,
    maxRetries: args.maxRetries,
    unreachableRetryEnabled: args.unreachableRetryEnabled,
    unreachableMaxRetries: args.unreachableMaxRetries,
  };
}

export async function probeWaitHit(args: {
  key: string;
  lineHint?: number;
  baseUrl: string;
  statusPath: string;
  timeoutMs?: number;
  pollIntervalMs?: number;
  maxRetries?: number;
  unreachableRetryEnabled?: boolean;
  unreachableMaxRetries?: number;
}): Promise<ToolTextResponse> {
  const timeoutMs = clampInt(args.timeoutMs ?? DEFAULT_PROBE_TIMEOUT_MS, 1_000, HARD_MAX_PROBE_TIMEOUT_MS);
  const pollIntervalMs = clampInt(
    args.pollIntervalMs ?? DEFAULT_PROBE_POLL_INTERVAL_MS,
    100,
    HARD_MAX_PROBE_POLL_INTERVAL_MS,
  );
  const maxRetries = clampInt(
    args.maxRetries ?? DEFAULT_PROBE_WAIT_MAX_RETRIES,
    1,
    HARD_MAX_PROBE_WAIT_MAX_RETRIES,
  );
  const unreachableRetryEnabled = args.unreachableRetryEnabled === true;
  const unreachableMaxRetries = clampInt(
    args.unreachableMaxRetries ?? DEFAULT_PROBE_WAIT_UNREACHABLE_MAX_RETRIES,
    1,
    HARD_MAX_PROBE_WAIT_UNREACHABLE_MAX_RETRIES,
  );
  const resolvedKey = resolveProbeKey(args.key, args.lineHint);
  const pollUrl = joinUrl(args.baseUrl, args.statusPath);

  if (!isLineKey(resolvedKey)) {
    return buildLineKeyRequiredResponse({
      request: buildSelectorRequest({
        key: args.key,
        resolvedKey,
        timeoutMs,
        pollIntervalMs,
        maxRetries,
        unreachableRetryEnabled,
        unreachableMaxRetries,
      }),
      targetPath: resolvedKey,
      httpRequest: `POLL ${pollUrl} (maxRetries=${maxRetries}, pollMs=${pollIntervalMs})`,
      requestMethod: "POLL",
      requestUrl: pollUrl,
      requestBody: {
        key: resolvedKey,
        maxRetries,
        pollIntervalMs,
        timeoutMs,
        unreachableRetryEnabled,
        unreachableMaxRetries,
      },
      result: {
        hit: false,
        inline: false,
        reason: "line_key_required",
        reasonCode: "line_key_required",
        nextActionCode: deriveNextActionCode("line_key_required"),
        reasonMeta: normalizeReasonMeta({ failedStep: "input_validation" }),
      },
      runNotes: "probe_wait_for_hit strict line mode",
    });
  }

  let last: Record<string, unknown> | null = null;
  let staleCandidate: Record<string, unknown> | undefined;
  const initialWindow = resolveProbeWaitWindow(resolvedKey);
  const triggerWindowStartMs = initialWindow.triggerWindowStartMs;

  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    const waitStartMs = attempt === 1 ? initialWindow.waitStartMs : Date.now();
    const requestCtx = {
      key: args.key,
      resolvedKey,
      timeoutMs,
      pollIntervalMs,
      maxRetries,
      unreachableRetryEnabled,
      unreachableMaxRetries,
      attempt,
      waitStartEpoch: waitStartMs,
      triggerWindowStartEpoch: triggerWindowStartMs,
    };

    const baselineStatus = await probeStatusWithUnreachablePolicy({
      key: resolvedKey,
      baseUrl: args.baseUrl,
      statusPath: args.statusPath,
      timeoutMs: Math.min(5_000, timeoutMs),
      pollIntervalMs,
      unreachableRetryEnabled,
      unreachableMaxRetries,
    });
    if (baselineStatus.kind === "unreachable") {
      return buildServiceUnreachableResponse({
        request: requestCtx,
        stage: "baseline_status_check",
        details: baselineStatus.details,
      });
    }

    const baselineRes = baselineStatus.response;
    const baselineJson = ((baselineRes.structuredContent as any)?.response?.json ?? null) as
      | Record<string, unknown>
      | null;
    const baselineLineValidation = readLineValidation(baselineJson);
    const baseline = parseProbeSnapshot(baselineRes.structuredContent);
    const baselineHitCount = baseline.hitCount ?? 0;
    const baselineLastHitEpoch = baseline.lastHitEpoch ?? 0;

    if (baselineLineValidation.invalidLineTarget) {
      return buildInvalidLineTargetResponse({
        request: requestCtx,
        pollUrl,
        baselineHitCount,
        lineValidation: baselineLineValidation.lineValidation ?? "invalid_line_target",
        lastStatus: baselineJson,
        runNotes: "probe_wait_for_hit invalid line target detected at baseline status",
      });
    }

    if (
      hasBaselineInlineHit({
        baselineHitCount,
        baselineLastHitEpoch,
        triggerWindowStartMs,
      })
    ) {
      return buildBaselineInlineHitResponse({
        request: requestCtx,
        pollUrl,
        baselineHitCount,
        baselineLastHitEpoch,
        lastStatus: (baselineJson ?? {}) as Record<string, unknown>,
      });
    }

    const start = waitStartMs;
    while (Date.now() - start < timeoutMs) {
      const polledStatus = await probeStatusWithUnreachablePolicy({
        key: resolvedKey,
        baseUrl: args.baseUrl,
        statusPath: args.statusPath,
        timeoutMs: Math.min(5_000, timeoutMs),
        pollIntervalMs,
        unreachableRetryEnabled,
        unreachableMaxRetries,
      });
      if (polledStatus.kind === "unreachable") {
        return buildServiceUnreachableResponse({
          request: requestCtx,
          stage: "poll_status_check",
          baseline: { hitCount: baselineHitCount, lastHitEpoch: baselineLastHitEpoch },
          details: polledStatus.details,
        });
      }

      const res = polledStatus.response;
      last = res.structuredContent;
      const json = (last as any)?.response?.json as Record<string, unknown> | undefined;
      const probeJson =
        json && typeof json.probe === "object" && json.probe !== null
          ? (json.probe as Record<string, unknown>)
          : undefined;
      const lineValidation = readLineValidation(json ?? null);
      if (lineValidation.invalidLineTarget) {
        return buildInvalidLineTargetResponse({
          request: requestCtx,
          pollUrl,
          baselineHitCount,
          lineValidation: lineValidation.lineValidation ?? "invalid_line_target",
          lastStatus: json ?? null,
          runNotes: "probe_wait_for_hit invalid line target detected during poll",
          baseline: { hitCount: baselineHitCount, lastHitEpoch: baselineLastHitEpoch },
        });
      }

      const hitCount =
        typeof probeJson?.hitCount === "number"
          ? probeJson.hitCount
          : typeof json?.hitCount === "number"
            ? json.hitCount
            : null;
      const lastHitEpoch =
        typeof probeJson?.lastHitEpoch === "number"
          ? probeJson.lastHitEpoch
          : typeof json?.lastHitEpoch === "number"
            ? json.lastHitEpoch
            : null;
      if (hitCount !== null) {
        const hitDelta = hitCount - baselineHitCount;
        const inlineByCount = hitDelta > 0;
        const inlineByTime = isInlineByTime(lastHitEpoch, triggerWindowStartMs);
        if (inlineByCount && inlineByTime) {
          return buildPolledInlineHitResponse({
            request: requestCtx,
            pollUrl,
            baselineHitCount,
            baselineLastHitEpoch,
            hitCount,
            hitDelta,
            lastStatus: json ?? {},
          });
        }
        if (inlineByCount && !inlineByTime) {
          staleCandidate = {
            hitCount,
            hitDelta,
            lastHitEpoch,
            reason: "hit_count_changed_but_not_inline_to_current_wait_window",
          };
        }
      }
      await sleep(pollIntervalMs);
    }
  }

  return buildTimeoutNoInlineHitResponse({
    key: args.key,
    resolvedKey,
    timeoutMs,
    pollIntervalMs,
    maxRetries,
    unreachableRetryEnabled,
    unreachableMaxRetries,
    pollUrl,
    last,
    staleCandidate,
  });
}
