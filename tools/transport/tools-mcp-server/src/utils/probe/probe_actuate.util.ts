import { fetchJson } from "@/lib/http";
import { clampInt, DEFAULT_PROBE_TIMEOUT_MS, HARD_MAX_PROBE_TIMEOUT_MS } from "@/lib/safety";
import type { ToolTextResponse } from "@tools-contracts/output";
import { deriveNextActionCode, normalizeReasonMeta } from "@/utils/failure_diagnostics.util";
import { joinUrl, probeUnreachableMessage } from "@/utils/probe.util";
import { isLineKey } from "@/utils/probe/key.util";
import { formatProbeOutput } from "@/utils/probe/output.util";
import { buildTextResponse } from "@/utils/probe/response_builders.util";

export async function probeActuate(args: {
  baseUrl: string;
  actuatePath: string;
  action: "arm" | "disarm";
  sessionId: string;
  actuatorId?: string;
  targetKey?: string;
  returnBoolean?: boolean;
  ttlMs?: number;
  timeoutMs?: number;
}): Promise<ToolTextResponse> {
  const timeoutMs = clampInt(args.timeoutMs ?? DEFAULT_PROBE_TIMEOUT_MS, 1_000, HARD_MAX_PROBE_TIMEOUT_MS);
  const url = joinUrl(args.baseUrl, args.actuatePath);

  if (!args.sessionId || args.sessionId.trim().length === 0) {
    const reasonCode = "session_id_required";
    const structuredContent: Record<string, unknown> = {
      request: {
        action: args.action,
        sessionId: args.sessionId,
        targetKey: args.targetKey,
        returnBoolean: args.returnBoolean,
        ttlMs: args.ttlMs,
        timeoutMs,
      },
      result: {
        actuated: false,
        reason: reasonCode,
        reasonCode,
        nextActionCode: deriveNextActionCode(reasonCode),
        reasonMeta: normalizeReasonMeta({ failedStep: "input_validation", action: args.action }),
      },
    };
    const text = formatProbeOutput({
      probeKey: args.targetKey ?? "probe_actuation",
      httpRequest: `POST ${url}`,
      requestMethod: "POST",
      requestUrl: url,
      requestHeaders: { "content-type": "application/json" },
      requestBody: {
        action: args.action,
        sessionId: args.sessionId,
        targetKey: args.targetKey,
        returnBoolean: args.returnBoolean,
        ttlMs: args.ttlMs,
        actuatorId: args.actuatorId,
      },
      executionHit: "not_hit",
      apiOutcome: "error",
      reproStatus: "session_id_required",
      probeHit: "sessionId is required for session-scoped probe actuation",
      httpCode: 400,
      httpResponse: structuredContent.result,
      runtimeMode: "observe",
      runDuration: "Not measured",
      runNotes: "probe_enable strict line mode",
    });
    return buildTextResponse(structuredContent, text);
  }

  if (args.action === "arm") {
    if (!args.targetKey || !isLineKey(args.targetKey)) {
      const reasonCode = "line_key_required";
      const structuredContent: Record<string, unknown> = {
        request: {
          action: args.action,
          sessionId: args.sessionId,
          targetKey: args.targetKey,
          returnBoolean: args.returnBoolean,
          ttlMs: args.ttlMs,
          timeoutMs,
        },
        result: {
          actuated: false,
          reason: reasonCode,
          reasonCode,
          nextActionCode: deriveNextActionCode(reasonCode),
          reasonMeta: normalizeReasonMeta({ failedStep: "input_validation", action: args.action }),
        },
      };
      const text = formatProbeOutput({
        probeKey: args.targetKey ?? "probe_actuation",
        httpRequest: `POST ${url}`,
        requestMethod: "POST",
        requestUrl: url,
        requestHeaders: { "content-type": "application/json" },
        requestBody: {
          action: args.action,
          sessionId: args.sessionId,
          targetKey: args.targetKey,
          returnBoolean: args.returnBoolean,
          ttlMs: args.ttlMs,
          actuatorId: args.actuatorId,
        },
        executionHit: "not_hit",
        apiOutcome: "error",
        reproStatus: "line_key_required",
        probeHit: "line targetKey required for branch actuation (Class#method:<line>)",
        httpCode: 400,
        httpResponse: structuredContent.result,
        runtimeMode: "observe",
        runDuration: "Not measured",
        runNotes: "probe_enable strict line mode",
      });
      return buildTextResponse(structuredContent, text);
    }
    if (typeof args.returnBoolean !== "boolean") {
      const reasonCode = "return_boolean_required";
      const structuredContent: Record<string, unknown> = {
        request: {
          action: args.action,
          sessionId: args.sessionId,
          targetKey: args.targetKey,
          returnBoolean: args.returnBoolean,
          ttlMs: args.ttlMs,
          timeoutMs,
        },
        result: {
          actuated: false,
          reason: reasonCode,
          reasonCode,
          nextActionCode: deriveNextActionCode(reasonCode),
          reasonMeta: normalizeReasonMeta({ failedStep: "input_validation", action: args.action }),
        },
      };
      return buildTextResponse(structuredContent, JSON.stringify(structuredContent.result));
    }
    if (typeof args.ttlMs !== "number" || !Number.isInteger(args.ttlMs) || args.ttlMs <= 0) {
      const reasonCode = "ttl_required";
      const structuredContent: Record<string, unknown> = {
        request: {
          action: args.action,
          sessionId: args.sessionId,
          targetKey: args.targetKey,
          returnBoolean: args.returnBoolean,
          ttlMs: args.ttlMs,
          timeoutMs,
        },
        result: {
          actuated: false,
          reason: reasonCode,
          reasonCode,
          nextActionCode: deriveNextActionCode(reasonCode),
          reasonMeta: normalizeReasonMeta({ failedStep: "input_validation", action: args.action }),
        },
      };
      return buildTextResponse(structuredContent, JSON.stringify(structuredContent.result));
    }
  }

  if (args.action === "disarm" && (args.targetKey || typeof args.returnBoolean === "boolean" || typeof args.ttlMs === "number")) {
    const reasonCode = "disarm_fields_not_allowed";
    const structuredContent: Record<string, unknown> = {
      request: {
        action: args.action,
        sessionId: args.sessionId,
        targetKey: args.targetKey,
        returnBoolean: args.returnBoolean,
        ttlMs: args.ttlMs,
        timeoutMs,
      },
      result: {
        actuated: false,
        reason: reasonCode,
        reasonCode,
        nextActionCode: deriveNextActionCode(reasonCode),
        reasonMeta: normalizeReasonMeta({ failedStep: "input_validation", action: args.action }),
      },
    };
    return buildTextResponse(structuredContent, JSON.stringify(structuredContent.result));
  }

  const body: Record<string, unknown> = {};
  body.action = args.action;
  body.sessionId = args.sessionId;
  if (typeof args.actuatorId === "string") body.actuatorId = args.actuatorId;
  if (typeof args.targetKey === "string") body.targetKey = args.targetKey;
  if (typeof args.returnBoolean === "boolean") body.returnBoolean = args.returnBoolean;
  if (typeof args.ttlMs === "number") body.ttlMs = args.ttlMs;

  let res;
  try {
    res = await fetchJson(url, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
      timeoutMs,
    });
  } catch (err) {
    throw new Error(probeUnreachableMessage(url, err));
  }

  const structuredContent: Record<string, unknown> = {
    request: { url, timeoutMs, body },
    response: { status: res.status, json: res.json, text: res.json ? undefined : res.text },
  };
  const json = (res.json as Record<string, unknown> | null) ?? {};
  const effectiveMode = typeof json.mode === "string" ? json.mode : (args.action === "arm" ? "actuate" : "observe");
  const effectiveAction = typeof json.action === "string" ? json.action : args.action;
  const effectiveSessionId = typeof json.sessionId === "string" ? json.sessionId : args.sessionId;
  const scopeState = typeof json.scopeState === "string" ? json.scopeState : (args.action === "arm" ? "armed" : "disarmed");
  const expiresAtEpoch = typeof json.expiresAtEpoch === "number" ? json.expiresAtEpoch : undefined;
  const effectiveActuatorId = typeof json.actuatorId === "string" ? json.actuatorId : (args.actuatorId ?? "");
  const effectiveTargetKey = typeof json.targetKey === "string" ? json.targetKey : (args.targetKey ?? "");
  const branchDecision =
    typeof json.returnBoolean === "boolean"
      ? json.returnBoolean
      : typeof args.returnBoolean === "boolean"
        ? args.returnBoolean
        : undefined;

  const text = formatProbeOutput({
    probeKey: effectiveTargetKey || "probe_actuation",
    httpRequest: `POST ${url}`,
    requestMethod: "POST",
    requestUrl: url,
    requestHeaders: { "content-type": "application/json" },
    requestBody: body,
    executionHit: "not_applicable",
    apiOutcome: res.status >= 200 && res.status < 300 ? "ok" : "error",
    reproStatus: res.status >= 200 && res.status < 300 ? "actuation_applied" : "actuation_failed",
    probeHit:
      `action=${effectiveAction}, mode=${effectiveMode}, sessionId=${effectiveSessionId}, scopeState=${scopeState}, actuatorId=${effectiveActuatorId || "(none)"}` +
      (typeof branchDecision === "boolean"
        ? `, branchDecision=${branchDecision ? "taken" : "fallthrough"}`
        : "") +
      (typeof expiresAtEpoch === "number" ? `, expiresAtEpoch=${expiresAtEpoch}` : ""),
    httpCode: res.status,
    httpResponse: res.json ?? res.text,
    runtimeMode: effectiveMode,
    runDuration: "Not measured",
    runNotes:
      "probe_enable executed (branch forcing applies only to conditional jump opcodes encountered on targetKey line)",
  });

  return buildTextResponse(structuredContent, text);
}
