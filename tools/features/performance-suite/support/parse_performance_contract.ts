function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asTrimmedString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value.trim() : undefined;
}

function asPositiveInteger(value: unknown): number | undefined {
  return typeof value === "number" && Number.isInteger(value) && value > 0 ? value : undefined;
}

function asNonNegativeInteger(value: unknown): number | undefined {
  return typeof value === "number" && Number.isInteger(value) && value >= 0 ? value : undefined;
}
import type { PerformanceMstaSummary } from "../performance_msta_summary";
import type { PerformanceWorkloadProvider } from "../../performance-workload-jmeter";
import { resolvePerformanceWorkloadProvider } from "./resolve_performance_workload_provider";

export type PerformanceEntrypoint = {
  transport: {
    protocol: "http";
    baseUrl: string;
    healthCheckPath?: string;
    wrappedOnly?: boolean;
    defaultHeaders?: Record<string, string>;
  };
  request: {
    method: string;
    path: string;
    queryTemplate?: Record<string, unknown>;
    headers?: Record<string, string>;
    body?: unknown;
  };
};

export type PerformancePlanContract = {
  entrypoints: PerformanceEntrypoint[];
  workloadProvider: PerformanceWorkloadProvider;
  observationTargets: {
    requiredLineHits: string[];
    optionalLineHits?: string[];
    probeId?: string;
  };
  loadModel: {
    mode: "concurrency";
    concurrency: number;
    rampUpSeconds: number;
    durationSeconds: number;
  };
  successCriteria: {
    maxErrorRatePct: number;
    minThroughputPerSec: number;
    p95LatencyMs: number;
  };
  analysis?: {
    executionTiming?: {
      enabled: true;
      provider: "async-profiler";
      event?: string;
      intervalNanos?: number;
      outputFormat?: "jfr";
    };
    msta?: {
      enabled: true;
      mode?: "method_targets" | "target_plus_path";
      methodTargets: Array<{
        methodRef: string;
      }>;
      includePackages?: string[];
      allowThirdPartyFrames?: boolean;
    };
  };
};

export type PersistedPerformanceMstaSummary =
  | PerformanceMstaSummary
  | {
      status: "not_configured" | "disabled";
    };

export type PerformanceMstaConfigState = PersistedPerformanceMstaSummary["status"];

export function parseStringRecord(value: unknown): Record<string, string> | undefined {
  if (!isRecord(value)) return undefined;
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(value)) {
    const text = asTrimmedString(v);
    if (text) out[k] = text;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

export function parsePerformanceContract(
  input: unknown,
): {
  ok: true;
  contract: PerformancePlanContract;
  mstaConfigState: PerformanceMstaConfigState;
} | { ok: false; reasonCode: string; requiredUserAction: string[] } {
  if (!isRecord(input)) {
    return {
      ok: false,
      reasonCode: "performance_plan_invalid",
      requiredUserAction: ["Set contract.json as an object for the performance plan."],
    };
  }
  const rawEntrypoints = Array.isArray(input.entrypoints) ? input.entrypoints : [];
  if (rawEntrypoints.length === 0) {
    return {
      ok: false,
      reasonCode: "performance_plan_invalid",
      requiredUserAction: ["Add at least one entrypoint to contract.entrypoints[]."],
    };
  }
  if (rawEntrypoints.length !== 1) {
    return {
      ok: false,
      reasonCode: "performance_entrypoint_unsupported",
      requiredUserAction: ["Current performance executor supports exactly one contract.entrypoints[] entry."],
    };
  }
  const parsedEntrypoints: PerformanceEntrypoint[] = [];
  for (const raw of rawEntrypoints) {
    if (!isRecord(raw) || !isRecord(raw.transport) || !isRecord(raw.request)) {
      return {
        ok: false,
        reasonCode: "performance_plan_invalid",
        requiredUserAction: ["Set each contract.entrypoints[] item with transport and request objects."],
      };
    }
    const protocol = asTrimmedString(raw.transport.protocol);
    const baseUrl = asTrimmedString(raw.transport.baseUrl);
    const method = asTrimmedString(raw.request.method);
    const requestPath = asTrimmedString(raw.request.path);
    if (protocol !== "http" || !baseUrl || !method || !requestPath) {
      return {
        ok: false,
        reasonCode: "performance_plan_invalid",
        requiredUserAction: ["Set entrypoint transport.protocol=http, transport.baseUrl, request.method, and request.path."],
      };
    }
    const defaultHeaders = parseStringRecord(raw.transport.defaultHeaders);
    const requestHeaders = parseStringRecord(raw.request.headers);
    parsedEntrypoints.push({
      transport: {
        protocol: "http",
        baseUrl,
        ...(typeof raw.transport.healthCheckPath === "string" ? { healthCheckPath: raw.transport.healthCheckPath } : {}),
        ...(typeof raw.transport.wrappedOnly === "boolean" ? { wrappedOnly: raw.transport.wrappedOnly } : {}),
        ...(defaultHeaders ? { defaultHeaders } : {}),
      },
      request: {
        method,
        path: requestPath,
        ...(isRecord(raw.request.queryTemplate) ? { queryTemplate: raw.request.queryTemplate } : {}),
        ...(requestHeaders ? { headers: requestHeaders } : {}),
        ...("body" in raw.request ? { body: raw.request.body } : {}),
      },
    });
  }

  const observationTargets = isRecord(input.observationTargets) ? input.observationTargets : null;
  const requiredLineHits = Array.isArray(observationTargets?.requiredLineHits)
    ? observationTargets.requiredLineHits
        .filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0)
        .map((entry) => entry.trim())
    : [];
  const optionalLineHits = Array.isArray(observationTargets?.optionalLineHits)
    ? observationTargets.optionalLineHits
        .filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0)
        .map((entry) => entry.trim())
    : [];
  const probeId = asTrimmedString(observationTargets?.probeId);
  if (requiredLineHits.length === 0) {
    return {
      ok: false,
      reasonCode: "performance_required_line_hit_missing",
      requiredUserAction: ["Set observationTargets.requiredLineHits[] with at least one Strict Line Key."],
    };
  }

  const loadModel = isRecord(input.loadModel) ? input.loadModel : null;
  const mode = loadModel ? asTrimmedString(loadModel.mode) : undefined;
  const concurrency = loadModel ? asPositiveInteger(loadModel.concurrency) : undefined;
  const rampUpSeconds = loadModel ? asNonNegativeInteger(loadModel.rampUpSeconds) : undefined;
  const durationSeconds = loadModel ? asPositiveInteger(loadModel.durationSeconds) : undefined;
  if (mode !== "concurrency") {
    return {
      ok: false,
      reasonCode: "performance_load_model_unsupported",
      requiredUserAction: ["Set loadModel.mode=concurrency for the current performance executor."],
    };
  }
  if (!concurrency || typeof rampUpSeconds !== "number" || !durationSeconds) {
    return {
      ok: false,
      reasonCode: "performance_plan_invalid",
      requiredUserAction: ["Set loadModel.concurrency, loadModel.rampUpSeconds, and loadModel.durationSeconds."],
    };
  }

  const successCriteria = isRecord(input.successCriteria) ? input.successCriteria : null;
  const maxErrorRatePct = typeof successCriteria?.maxErrorRatePct === "number" ? successCriteria.maxErrorRatePct : undefined;
  const minThroughputPerSec = typeof successCriteria?.minThroughputPerSec === "number" ? successCriteria.minThroughputPerSec : undefined;
  const p95LatencyMs = typeof successCriteria?.p95LatencyMs === "number" ? successCriteria.p95LatencyMs : undefined;
  if (
    typeof maxErrorRatePct !== "number" ||
    typeof minThroughputPerSec !== "number" ||
    typeof p95LatencyMs !== "number" ||
    maxErrorRatePct < 0 ||
    minThroughputPerSec <= 0 ||
    p95LatencyMs <= 0
  ) {
    return {
      ok: false,
      reasonCode: "performance_threshold_invalid",
      requiredUserAction: ["Set deterministic successCriteria.maxErrorRatePct, minThroughputPerSec, and p95LatencyMs."],
    };
  }

  const executionTiming = resolveExecutionTiming(input);
  const mstaValidationError = validateMstaConfig(input);
  const msta = resolveMsta(input);
  const mstaConfigState = resolveMstaConfigState(input);
  const workloadProvider = resolvePerformanceWorkloadProvider(input);
  if (!workloadProvider.ok) {
    return workloadProvider;
  }
  if (mstaValidationError) {
    return {
      ok: false,
      reasonCode: "performance_plan_invalid",
      requiredUserAction: mstaValidationError,
    };
  }
  if (msta && !executionTiming) {
    return {
      ok: false,
      reasonCode: "performance_plan_invalid",
      requiredUserAction: ["Set analysis.executionTiming when analysis.msta is enabled."],
    };
  }

  return {
    ok: true,
    contract: {
      entrypoints: parsedEntrypoints,
      workloadProvider: workloadProvider.provider,
      observationTargets: {
        requiredLineHits,
        ...(optionalLineHits.length > 0 ? { optionalLineHits } : {}),
        ...(probeId ? { probeId } : {}),
      },
      loadModel: {
        mode: "concurrency",
        concurrency,
        rampUpSeconds,
        durationSeconds,
      },
      successCriteria: {
        maxErrorRatePct,
        minThroughputPerSec,
        p95LatencyMs,
      },
      ...(executionTiming || msta
        ? {
            analysis: {
              ...(executionTiming ? { executionTiming } : {}),
              ...(msta ? { msta } : {}),
            },
          }
        : {}),
    },
    mstaConfigState,
  };
}

export function isProfilerDownloadSuccess(value: unknown): boolean {
  if (!isRecord(value)) return false;
  const result = isRecord(value.result) ? value.result : null;
  return result?.status === "downloaded";
}

export function isProfilerDownloadNotReady(value: unknown): boolean {
  if (!isRecord(value)) return false;
  const response = isRecord(value.response) ? value.response : null;
  const responseJson = isRecord(response?.json) ? response?.json : null;
  return response?.status === 404 && responseJson?.error === "profiler_output_not_found";
}

export function resolveProfilerStartFailure(value: unknown): { reasonCode: string; detail: string } | null {
  if (!isRecord(value)) {
    return {
      reasonCode: "performance_profiler_start_failed",
      detail: "Profiler start response is missing structured content.",
    };
  }

  const response = isRecord(value.response) ? value.response : null;
  const responseStatus = typeof response?.status === "number" ? response.status : undefined;
  const responseJson = isRecord(response?.json) ? response.json : null;
  const responseError = asTrimmedString(responseJson?.error);
  const result = isRecord(value.result) ? value.result : null;
  const supported = result?.supported === false ? false : result?.supported === true ? true : undefined;
  const status = asTrimmedString(result?.status);
  const detail = asTrimmedString(result?.detail) ?? responseError;

  if (typeof responseStatus === "number" && responseStatus >= 400) {
    return {
      reasonCode: responseError ?? "performance_profiler_start_failed",
      detail: detail ?? `Probe profiler start returned HTTP ${String(responseStatus)}.`,
    };
  }
  if (supported === false) {
    return {
      reasonCode: detail ?? "performance_profiler_unsupported",
      detail: detail ?? "Profiler provider is unsupported for the active JVM runtime.",
    };
  }
  if (status === "failed") {
    return {
      reasonCode: detail ?? "performance_profiler_start_failed",
      detail: detail ?? "Profiler provider failed to start.",
    };
  }

  return null;
}

export function resolveExecutionTiming(
  input: Record<string, unknown>,
): PerformancePlanContract["analysis"] extends { executionTiming?: infer T } ? T | undefined : never {
  const analysis = isRecord(input.analysis) ? input.analysis : null;
  const executionTiming = analysis && isRecord(analysis.executionTiming) ? analysis.executionTiming : null;
  if (!executionTiming || executionTiming.enabled !== true) return undefined as never;
  const provider = asTrimmedString(executionTiming.provider);
  const event = asTrimmedString(executionTiming.event);
  const intervalNanos = asPositiveInteger(executionTiming.intervalNanos);
  const outputFormat = asTrimmedString(executionTiming.outputFormat);
  if (provider !== "async-profiler") return undefined as never;
  return {
    enabled: true,
    provider: "async-profiler",
    ...(event ? { event } : {}),
    ...(typeof intervalNanos === "number" ? { intervalNanos } : {}),
    ...(outputFormat === "jfr" ? { outputFormat: "jfr" as const } : {}),
  } as never;
}

export function resolveMsta(
  input: Record<string, unknown>,
): PerformancePlanContract["analysis"] extends { msta?: infer T } ? T | undefined : never {
  const analysis = isRecord(input.analysis) ? input.analysis : null;
  const msta = analysis && isRecord(analysis.msta) ? analysis.msta : null;
  if (!msta || msta.enabled !== true) return undefined as never;
  const mode = asTrimmedString(msta.mode);
  const rawTargets = Array.isArray(msta.methodTargets) ? msta.methodTargets : [];
  const methodTargets = rawTargets
    .map((entry) => {
      if (typeof entry === "string") {
        const methodRef = asTrimmedString(entry);
        return methodRef ? { methodRef } : null;
      }
      if (!isRecord(entry)) return null;
      const methodRef = asTrimmedString(entry.methodRef);
      return methodRef ? { methodRef } : null;
    })
    .filter((entry): entry is { methodRef: string } => entry !== null);
  if (methodTargets.length === 0) return undefined as never;
  const includePackages = Array.isArray(msta.includePackages)
    ? msta.includePackages
        .filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0)
        .map((entry) => entry.trim())
    : [];
  return {
    enabled: true,
    ...((mode === "method_targets" || mode === "target_plus_path") ? { mode } : {}),
    methodTargets,
    ...(includePackages.length > 0 ? { includePackages } : {}),
    ...(typeof msta.allowThirdPartyFrames === "boolean" ? { allowThirdPartyFrames: msta.allowThirdPartyFrames } : {}),
  } as never;
}

export function resolveRawMstaConfig(input: Record<string, unknown>): unknown {
  const analysis = isRecord(input.analysis) ? input.analysis : null;
  return analysis ? analysis.msta : undefined;
}

export function validateMstaConfig(input: Record<string, unknown>): string[] | undefined {
  const rawMsta = resolveRawMstaConfig(input);
  if (typeof rawMsta === "undefined") return undefined;
  if (!isRecord(rawMsta)) {
    return ["Set analysis.msta as an object when MSTA configuration is present."];
  }
  if (rawMsta.enabled === false) {
    return undefined;
  }
  if (rawMsta.enabled !== true) {
    return ["Set analysis.msta.enabled=true or remove analysis.msta when MSTA is not configured."];
  }
  const rawTargets = Array.isArray(rawMsta.methodTargets) ? rawMsta.methodTargets : [];
  const hasMethodRef = rawTargets.some((entry) => {
    if (typeof entry === "string") return entry.trim().length > 0;
    if (!isRecord(entry)) return false;
    return typeof entry.methodRef === "string" && entry.methodRef.trim().length > 0;
  });
  if (!hasMethodRef) {
    return ["Set analysis.msta.methodTargets[] with at least one methodRef when analysis.msta is enabled."];
  }
  if ("mode" in rawMsta && typeof rawMsta.mode !== "undefined" && rawMsta.mode !== "method_targets" && rawMsta.mode !== "target_plus_path") {
    return ["Set analysis.msta.mode to method_targets or target_plus_path when provided."];
  }
  return undefined;
}

export function resolveMstaConfigState(input: Record<string, unknown>): PerformanceMstaConfigState {
  const rawMsta = resolveRawMstaConfig(input);
  if (typeof rawMsta === "undefined") return "not_configured";
  if (isRecord(rawMsta) && rawMsta.enabled === false) return "disabled";
  return "available";
}

export function buildPersistedMstaSummary(args: {
  mstaConfigState: PerformanceMstaConfigState;
  materializedSummary?: PerformanceMstaSummary;
}): PersistedPerformanceMstaSummary {
  if (args.materializedSummary) {
    return args.materializedSummary;
  }
  if (args.mstaConfigState === "disabled") {
    return { status: "disabled" };
  }
  return { status: "not_configured" };
}
