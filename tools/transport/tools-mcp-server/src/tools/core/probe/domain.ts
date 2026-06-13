import { probeActuate as probeActuateUtil } from "@/utils/probe/probe_actuate.util";
import { probeCaptureGet as probeCaptureGetUtil } from "@/utils/probe/probe_capture_get.util";
import { probeReset as probeResetUtil } from "@/utils/probe/probe_reset.util";
import { resolveProbeBaseUrl } from "@/utils/probe/probe_route_resolver.util";
import { probeStatus as probeStatusUtil } from "@/utils/probe/probe_status.util";
import { probeWaitHit as probeWaitHitUtil } from "@/utils/probe/probe_wait_hit.util";
import { fetchJson } from "@/lib/http";
import { clampInt, DEFAULT_PROBE_TIMEOUT_MS, HARD_MAX_PROBE_TIMEOUT_MS } from "@/lib/safety";
import { deriveNextActionCode, normalizeReasonMeta } from "@/utils/failure_diagnostics.util";
import { joinUrl } from "@/utils/probe.util";
import { formatProbeOutput } from "@/utils/probe/output.util";
import type { ProbeRegistry } from "@/config/probe-registry";

export type ProbeDomainConfig = {
  probeBaseUrl: string;
  probeStatusPath: string;
  probeResetPath: string;
  probeActuatePath: string;
  probeCapturePath: string;
  probeWaitMaxRetries: number;
  probeWaitUnreachableRetryEnabled: boolean;
  probeWaitUnreachableMaxRetries: number;
  getProbeRegistry?: () => ProbeRegistry | undefined;
};

export type ProbeEnableInput = {
  baseUrl?: string | undefined;
  probeId?: string | undefined;
  action: "arm" | "disarm";
  sessionId: string;
  actuatorId?: string | undefined;
  targetKey?: string | undefined;
  returnBoolean?: boolean | undefined;
  ttlMs?: number | undefined;
  timeoutMs?: number | undefined;
};

export type ProbeCheckInput = {
  baseUrl?: string | undefined;
  probeId?: string | undefined;
  http?:
    | {
        headers?: Record<string, string> | undefined;
      }
    | undefined;
  timeoutMs?: number | undefined;
};

export type ProbeGetCaptureInput = {
  captureId: string;
  baseUrl?: string | undefined;
  probeId?: string | undefined;
  timeoutMs?: number | undefined;
};

export type ProbeGetStatusInput = {
  key?: string | undefined;
  keys?: string[] | undefined;
  lineHint?: number | undefined;
  baseUrl?: string | undefined;
  probeId?: string | undefined;
  timeoutMs?: number | undefined;
};

export type ProbeResetInput = {
  key?: string | undefined;
  keys?: string[] | undefined;
  className?: string | undefined;
  lineHint?: number | undefined;
  baseUrl?: string | undefined;
  probeId?: string | undefined;
  timeoutMs?: number | undefined;
};

export type ProbeWaitForHitInput = {
  key: string;
  lineHint?: number | undefined;
  baseUrl?: string | undefined;
  probeId?: string | undefined;
  timeoutMs?: number | undefined;
  pollIntervalMs?: number | undefined;
  maxRetries?: number | undefined;
};

function sanitizeRuntime(runtime: unknown): Record<string, unknown> | undefined {
  if (!runtime || typeof runtime !== "object") return undefined;
  const input = runtime as Record<string, unknown>;
  const out: Record<string, unknown> = { ...input };

  delete out.serverEpoch;
  delete out.applicationType;

  const appPort =
    typeof out.appPort === "object" && out.appPort !== null
      ? (out.appPort as Record<string, unknown>)
      : undefined;
  if (appPort) {
    delete appPort.confidence;
    out.appPort = appPort;
  }

  return out;
}

function sanitizeCheckPayload(json: unknown): Record<string, unknown> | null {
  if (!json || typeof json !== "object") return null;
  const out = { ...(json as Record<string, unknown>) };
  delete out.contractVersion;
  const runtime = sanitizeRuntime(out.runtime);
  if (runtime && Object.keys(runtime).length > 0) {
    out.runtime = runtime;
  } else {
    delete out.runtime;
  }
  return out;
}

export async function probeDiagnose(args: {
  baseUrl: string;
  statusPath: string;
  resetPath: string;
  http?: {
    headers?: Record<string, string>;
  };
  timeoutMs?: number;
}): Promise<{
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
}> {
  const timeoutMs = clampInt(
    args.timeoutMs ?? DEFAULT_PROBE_TIMEOUT_MS,
    1_000,
    HARD_MAX_PROBE_TIMEOUT_MS,
  );

  const probeKey = "mcp.jvm.diagnose#key";
  const statusUrl = new URL(joinUrl(args.baseUrl, args.statusPath));
  statusUrl.searchParams.set("key", probeKey);
  const resetUrl = joinUrl(args.baseUrl, args.resetPath);
  const checks: Record<string, unknown> = {};
  const recommendations: string[] = [];
  const requestHeaders: Record<string, string> = {};
  for (const [name, value] of Object.entries(args.http?.headers ?? {})) {
    const normalizedName = name.trim();
    const normalizedValue = String(value).trim();
    if (!normalizedName || !normalizedValue) continue;
    requestHeaders[normalizedName] = normalizedValue;
  }
  let contractVersion: string | undefined;

  try {
    const reset = await fetchJson(resetUrl, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        ...requestHeaders,
      },
      body: JSON.stringify({ key: probeKey }),
      timeoutMs,
    });
    const resetJson = sanitizeCheckPayload(reset.json);
    if (!contractVersion && typeof reset.json?.contractVersion === "string") {
      contractVersion = reset.json.contractVersion;
    }
    checks.reset = {
      ok: reset.status >= 200 && reset.status < 300,
      status: reset.status,
      ...(resetJson ? { json: resetJson } : {}),
    };
    if (reset.status === 401 || reset.status === 403) {
      recommendations.push(
        "Probe reset endpoint is protected. Provide auth headers via probe.input.http.headers.",
      );
    }
  } catch (err) {
    checks.reset = { ok: false, error: err instanceof Error ? err.message : String(err) };
    recommendations.push(
      "Probe reset endpoint unreachable. Confirm docker service is running and probe port mapping is correct.",
    );
  }

  try {
    const status = await fetchJson(statusUrl.toString(), {
      method: "GET",
      headers: requestHeaders,
      timeoutMs,
    });
    const statusJson = sanitizeCheckPayload(status.json);
    if (!contractVersion && typeof status.json?.contractVersion === "string") {
      contractVersion = status.json.contractVersion;
    }
    const responseKey =
      typeof status.json?.probe?.key === "string"
        ? status.json.probe.key
        : typeof status.json?.key === "string"
          ? status.json.key
          : undefined;
    const statusOk = status.status >= 200 && status.status < 300;
    const decodeOk = statusOk ? responseKey === probeKey : undefined;
    checks.status = {
      ok: statusOk,
      status: status.status,
      ...(statusJson ? { json: statusJson } : {}),
      ...(typeof decodeOk === "boolean" ? { keyDecodingOk: decodeOk } : {}),
    };
    if (status.status === 401 || status.status === 403) {
      recommendations.push(
        "Probe status endpoint is protected. Provide auth headers via probe.input.http.headers.",
      );
    }
    if (decodeOk === false) {
      recommendations.push(
        "Probe key decoding mismatch detected. Rebuild/redeploy java-agent so query keys with # are decoded correctly.",
      );
    }
  } catch (err) {
    checks.status = { ok: false, error: err instanceof Error ? err.message : String(err) };
    recommendations.push(
      "Probe status endpoint unreachable. If port is unknown, ask user which service probe port is mapped.",
    );
  }

  const structuredContent: Record<string, unknown> = {
    config: {
      baseUrl: args.baseUrl,
      statusPath: args.statusPath,
      resetPath: args.resetPath,
      timeoutMs,
      authConfigured: Object.keys(requestHeaders).length > 0,
      authHeaderNames: Object.keys(requestHeaders),
    },
    checks,
    recommendations,
    ...(contractVersion ? { contractVersion } : {}),
  };
  const allOk =
    (checks.reset as { ok?: boolean }).ok === true &&
    (checks.status as { ok?: boolean; keyDecodingOk?: boolean }).ok === true &&
    (checks.status as { keyDecodingOk?: boolean }).keyDecodingOk !== false;
  if (!allOk) {
    const reasonCode = "diagnose_failed";
    structuredContent.status = "diagnose_failed";
    structuredContent.reasonCode = reasonCode;
    structuredContent.nextActionCode = deriveNextActionCode(reasonCode);
    structuredContent.reasonMeta = normalizeReasonMeta({
      failedStep: "probe_diagnostics",
      resetOk: (checks.reset as { ok?: boolean }).ok === true,
      statusOk: (checks.status as { ok?: boolean }).ok === true,
    });
  } else {
    structuredContent.status = "ok";
  }
  const text = formatProbeOutput({
    probeKey,
    httpRequest: `POST ${resetUrl} + GET ${statusUrl.toString()}`,
    requestMethod: "DIAGNOSE",
    requestUrl: statusUrl.toString(),
    executionHit: allOk ? "line_hit" : "not_hit",
    apiOutcome: allOk ? "ok" : "error",
    reproStatus: allOk ? "diagnose_ok" : "diagnose_failed",
    probeHit: allOk ? "probe wiring healthy" : "probe wiring has issues",
    httpCode: allOk ? 200 : "error",
    httpResponse: { checks, recommendations },
    runDuration: "Not measured",
    runNotes: recommendations.join(" | ") || "No recommendations",
  });

  return {
    content: [{ type: "text", text }],
    structuredContent,
  };
}

export function createProbeDomain(cfg: ProbeDomainConfig) {
  return {
    check: async (input: ProbeCheckInput) => {
      const base = resolveProbeBaseUrl({
        toolName: "probe",
        defaultBaseUrl: cfg.probeBaseUrl,
        ...(typeof input.probeId === "string" ? { probeId: input.probeId } : {}),
        ...(typeof input.baseUrl === "string" ? { baseUrl: input.baseUrl } : {}),
        ...(cfg.getProbeRegistry?.() ? { probeRegistry: cfg.getProbeRegistry?.() } : {}),
      });
      if (!base.ok) return base.response;
      const args: Parameters<typeof probeDiagnose>[0] = {
        baseUrl: base.baseUrl,
        statusPath: cfg.probeStatusPath,
        resetPath: cfg.probeResetPath,
      };
      if (input.http && typeof input.http === "object" && input.http.headers && typeof input.http.headers === "object") {
        args.http = { headers: input.http.headers };
      }
      if (typeof input.timeoutMs === "number") args.timeoutMs = input.timeoutMs;
      return await probeDiagnose(args);
    },
    enable: async (input: ProbeEnableInput) => {
      const base = resolveProbeBaseUrl({
        toolName: "probe",
        defaultBaseUrl: cfg.probeBaseUrl,
        ...(typeof input.probeId === "string" ? { probeId: input.probeId } : {}),
        ...(typeof input.baseUrl === "string" ? { baseUrl: input.baseUrl } : {}),
        ...(cfg.getProbeRegistry?.() ? { probeRegistry: cfg.getProbeRegistry?.() } : {}),
      });
      if (!base.ok) return base.response;
      const args: Parameters<typeof probeActuateUtil>[0] = {
        baseUrl: base.baseUrl,
        actuatePath: cfg.probeActuatePath,
        action: input.action,
        sessionId: input.sessionId,
      };
      if (typeof input.actuatorId === "string") args.actuatorId = input.actuatorId;
      if (typeof input.targetKey === "string") args.targetKey = input.targetKey;
      if (typeof input.returnBoolean === "boolean") args.returnBoolean = input.returnBoolean;
      if (typeof input.ttlMs === "number") args.ttlMs = input.ttlMs;
      if (typeof input.timeoutMs === "number") args.timeoutMs = input.timeoutMs;
      return await probeActuateUtil(args);
    },
    getCapture: async (input: ProbeGetCaptureInput) => {
      const base = resolveProbeBaseUrl({
        toolName: "probe",
        defaultBaseUrl: cfg.probeBaseUrl,
        ...(typeof input.probeId === "string" ? { probeId: input.probeId } : {}),
        ...(typeof input.baseUrl === "string" ? { baseUrl: input.baseUrl } : {}),
        ...(cfg.getProbeRegistry?.() ? { probeRegistry: cfg.getProbeRegistry?.() } : {}),
      });
      if (!base.ok) return base.response;
      const args: Parameters<typeof probeCaptureGetUtil>[0] = {
        captureId: input.captureId,
        baseUrl: base.baseUrl,
        capturePath: cfg.probeCapturePath,
      };
      if (typeof input.timeoutMs === "number") args.timeoutMs = input.timeoutMs;
      return await probeCaptureGetUtil(args);
    },
    getStatus: async (input: ProbeGetStatusInput) => {
      const base = resolveProbeBaseUrl({
        toolName: "probe",
        defaultBaseUrl: cfg.probeBaseUrl,
        ...(typeof input.probeId === "string" ? { probeId: input.probeId } : {}),
        ...(typeof input.baseUrl === "string" ? { baseUrl: input.baseUrl } : {}),
        ...(cfg.getProbeRegistry?.() ? { probeRegistry: cfg.getProbeRegistry?.() } : {}),
      });
      if (!base.ok) return base.response;
      const args: Parameters<typeof probeStatusUtil>[0] = {
        baseUrl: base.baseUrl,
        statusPath: cfg.probeStatusPath,
      };
      if (typeof input.key === "string") args.key = input.key;
      if (Array.isArray(input.keys)) args.keys = input.keys;
      if (typeof input.lineHint === "number") args.lineHint = input.lineHint;
      if (typeof input.timeoutMs === "number") args.timeoutMs = input.timeoutMs;
      return await probeStatusUtil(args);
    },
    reset: async (input: ProbeResetInput) => {
      const base = resolveProbeBaseUrl({
        toolName: "probe",
        defaultBaseUrl: cfg.probeBaseUrl,
        ...(typeof input.probeId === "string" ? { probeId: input.probeId } : {}),
        ...(typeof input.baseUrl === "string" ? { baseUrl: input.baseUrl } : {}),
        ...(cfg.getProbeRegistry?.() ? { probeRegistry: cfg.getProbeRegistry?.() } : {}),
      });
      if (!base.ok) return base.response;
      const args: Parameters<typeof probeResetUtil>[0] = {
        baseUrl: base.baseUrl,
        resetPath: cfg.probeResetPath,
      };
      if (typeof input.key === "string") args.key = input.key;
      if (Array.isArray(input.keys)) args.keys = input.keys;
      if (typeof input.className === "string") args.className = input.className;
      if (typeof input.lineHint === "number") args.lineHint = input.lineHint;
      if (typeof input.timeoutMs === "number") args.timeoutMs = input.timeoutMs;
      return await probeResetUtil(args);
    },
    waitForHit: async (input: ProbeWaitForHitInput) => {
      const base = resolveProbeBaseUrl({
        toolName: "probe",
        defaultBaseUrl: cfg.probeBaseUrl,
        ...(typeof input.probeId === "string" ? { probeId: input.probeId } : {}),
        ...(typeof input.baseUrl === "string" ? { baseUrl: input.baseUrl } : {}),
        ...(cfg.getProbeRegistry?.() ? { probeRegistry: cfg.getProbeRegistry?.() } : {}),
      });
      if (!base.ok) return base.response;
      const args: Parameters<typeof probeWaitHitUtil>[0] = {
        key: input.key,
        baseUrl: base.baseUrl,
        statusPath: cfg.probeStatusPath,
      };
      if (typeof input.lineHint === "number") args.lineHint = input.lineHint;
      if (typeof input.timeoutMs === "number") args.timeoutMs = input.timeoutMs;
      if (typeof input.pollIntervalMs === "number") args.pollIntervalMs = input.pollIntervalMs;
      args.maxRetries = typeof input.maxRetries === "number" ? input.maxRetries : cfg.probeWaitMaxRetries;
      args.unreachableRetryEnabled = cfg.probeWaitUnreachableRetryEnabled;
      args.unreachableMaxRetries = cfg.probeWaitUnreachableMaxRetries;
      return await probeWaitHitUtil(args);
    },
  };
}

// Direct domain exports are kept for unit tests and utility-level callers.
export async function probeStatus(args: Parameters<typeof probeStatusUtil>[0]) {
  return await probeStatusUtil(args);
}

export async function probeCaptureGet(args: Parameters<typeof probeCaptureGetUtil>[0]) {
  return await probeCaptureGetUtil(args);
}

export async function probeReset(args: Parameters<typeof probeResetUtil>[0]) {
  return await probeResetUtil(args);
}

export async function probeWaitHit(args: Parameters<typeof probeWaitHitUtil>[0]) {
  return await probeWaitHitUtil(args);
}

export async function probeActuate(args: Parameters<typeof probeActuateUtil>[0]) {
  return await probeActuateUtil(args);
}
