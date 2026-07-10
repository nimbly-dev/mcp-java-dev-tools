import { promises as fs } from "node:fs";
import path from "node:path";

import { asString, isRecord } from "../common";

export type PerformanceExportEntrypoint = {
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

export type PerformanceExportPlanContract = {
  entrypoints: PerformanceExportEntrypoint[];
  workloadProvider:
    | {
        type: "builtin";
      }
    | {
        type: "jmeter";
        mode: "generated_http";
        options?: {
          installationPath?: string;
          emitJmx?: boolean;
          emitJtl?: boolean;
          emitLog?: boolean;
        };
      };
  observationTargets: {
    requiredLineHits: string[];
    optionalLineHits?: string[];
    probeId?: string;
    baseUrl?: string;
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
  };
};

function parseRuntimeVerification(value: unknown): { probeId?: string; baseUrl?: string } | undefined {
  if (!isRecord(value)) return undefined;
  const probeId = asString(value.probeId);
  const baseUrl = asString(value.baseUrl);
  if (!probeId && !baseUrl) return undefined;
  return {
    ...(probeId ? { probeId } : {}),
    ...(baseUrl ? { baseUrl } : {}),
  };
}

function parseWorkloadProvider(
  value: unknown,
): PerformanceExportPlanContract["workloadProvider"] | null {
  if (!isRecord(value)) {
    return { type: "builtin" };
  }
  const type = asString(value.type);
  if (!type || type === "builtin") {
    return { type: "builtin" };
  }
  if (type !== "jmeter") {
    return null;
  }
  if (asString(value.mode) !== "generated_http") {
    return null;
  }
  const options = isRecord(value.options) ? value.options : null;
  const installationPath = asString(options?.installationPath);
  return {
    type: "jmeter",
    mode: "generated_http",
    ...(options
      ? {
          options: {
            ...(installationPath ? { installationPath } : {}),
            ...(typeof options.emitJmx === "boolean" ? { emitJmx: options.emitJmx } : {}),
            ...(typeof options.emitJtl === "boolean" ? { emitJtl: options.emitJtl } : {}),
            ...(typeof options.emitLog === "boolean" ? { emitLog: options.emitLog } : {}),
          },
        }
      : {}),
  };
}

function parseStringRecord(value: unknown): Record<string, string> | undefined {
  if (!isRecord(value)) return undefined;
  const out: Record<string, string> = {};
  for (const [key, raw] of Object.entries(value)) {
    const text = asString(raw);
    if (text) out[key] = text;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

export async function loadPerformancePlanContract(input: {
  plansRootAbs: string;
  planName: string;
}): Promise<PerformanceExportPlanContract | null> {
  const contractPathAbs = path.join(input.plansRootAbs, input.planName, "contract.json");
  try {
    const text = await fs.readFile(contractPathAbs, "utf8");
    const parsed = JSON.parse(text) as unknown;
    if (!isRecord(parsed)) return null;
    const rawEntrypoints = Array.isArray(parsed.entrypoints) ? parsed.entrypoints : [];
    if (rawEntrypoints.length !== 1) return null;
    const rawEntrypoint = rawEntrypoints[0];
    if (!isRecord(rawEntrypoint) || !isRecord(rawEntrypoint.transport) || !isRecord(rawEntrypoint.request)) {
      return null;
    }
    const baseUrl = asString(rawEntrypoint.transport.baseUrl);
    const method = asString(rawEntrypoint.request.method);
    const requestPath = asString(rawEntrypoint.request.path);
    if (asString(rawEntrypoint.transport.protocol) !== "http" || !baseUrl || !method || !requestPath) {
      return null;
    }
    const observationTargets = isRecord(parsed.observationTargets) ? parsed.observationTargets : null;
    const runtimeVerificationFallback =
      Array.isArray(parsed.targets) && parsed.targets.length > 0 && isRecord(parsed.targets[0])
        ? parseRuntimeVerification((parsed.targets[0] as Record<string, unknown>).runtimeVerification)
        : undefined;
    const requiredLineHits = Array.isArray(observationTargets?.requiredLineHits)
      ? observationTargets.requiredLineHits
          .filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0)
          .map((entry) => entry.trim())
      : [];
    if (requiredLineHits.length === 0) return null;
    const optionalLineHits = Array.isArray(observationTargets?.optionalLineHits)
      ? observationTargets.optionalLineHits
          .filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0)
          .map((entry) => entry.trim())
      : [];
    const loadModel = isRecord(parsed.loadModel) ? parsed.loadModel : null;
    if (
      asString(loadModel?.mode) !== "concurrency" ||
      typeof loadModel?.concurrency !== "number" ||
      typeof loadModel?.rampUpSeconds !== "number" ||
      typeof loadModel?.durationSeconds !== "number"
    ) {
      return null;
    }
    const successCriteria = isRecord(parsed.successCriteria) ? parsed.successCriteria : null;
    if (
      typeof successCriteria?.maxErrorRatePct !== "number" ||
      typeof successCriteria?.minThroughputPerSec !== "number" ||
      typeof successCriteria?.p95LatencyMs !== "number"
    ) {
      return null;
    }
    const analysis = isRecord(parsed.analysis) ? parsed.analysis : null;
    const workloadProvider = parseWorkloadProvider(parsed.workloadProvider);
    if (!workloadProvider) return null;
    const executionTiming = analysis && isRecord(analysis.executionTiming) ? analysis.executionTiming : null;
    const healthCheckPath = asString(rawEntrypoint.transport.healthCheckPath);
    const defaultHeaders = parseStringRecord(rawEntrypoint.transport.defaultHeaders);
    const queryTemplate = isRecord(rawEntrypoint.request.queryTemplate) ? rawEntrypoint.request.queryTemplate : undefined;
    const headers = parseStringRecord(rawEntrypoint.request.headers);
    const probeId = asString(observationTargets?.probeId) ?? runtimeVerificationFallback?.probeId;
    const observationBaseUrl = asString(observationTargets?.baseUrl) ?? runtimeVerificationFallback?.baseUrl;
    const executionTimingEvent = executionTiming ? asString(executionTiming.event) : undefined;
    const outputFormat = executionTiming ? asString(executionTiming.outputFormat) : undefined;
    return {
      entrypoints: [
        {
          transport: {
            protocol: "http",
            baseUrl,
            ...(healthCheckPath ? { healthCheckPath } : {}),
            ...(typeof rawEntrypoint.transport.wrappedOnly === "boolean"
              ? { wrappedOnly: rawEntrypoint.transport.wrappedOnly }
              : {}),
            ...(defaultHeaders ? { defaultHeaders } : {}),
          },
          request: {
            method,
            path: requestPath,
            ...(queryTemplate ? { queryTemplate } : {}),
            ...(headers ? { headers } : {}),
            ...("body" in rawEntrypoint.request ? { body: rawEntrypoint.request.body } : {}),
          },
        },
      ],
      workloadProvider,
      observationTargets: {
        requiredLineHits,
        ...(optionalLineHits.length > 0 ? { optionalLineHits } : {}),
        ...(probeId ? { probeId } : {}),
        ...(observationBaseUrl ? { baseUrl: observationBaseUrl } : {}),
      },
      loadModel: {
        mode: "concurrency",
        concurrency: loadModel.concurrency,
        rampUpSeconds: loadModel.rampUpSeconds,
        durationSeconds: loadModel.durationSeconds,
      },
      successCriteria: {
        maxErrorRatePct: successCriteria.maxErrorRatePct,
        minThroughputPerSec: successCriteria.minThroughputPerSec,
        p95LatencyMs: successCriteria.p95LatencyMs,
      },
      ...(executionTiming &&
      executionTiming.enabled === true &&
      asString(executionTiming.provider) === "async-profiler"
        ? {
            analysis: {
              executionTiming: {
                enabled: true,
                provider: "async-profiler",
                ...(executionTimingEvent ? { event: executionTimingEvent } : {}),
                ...(typeof executionTiming.intervalNanos === "number"
                  ? { intervalNanos: executionTiming.intervalNanos }
                  : {}),
                ...(outputFormat === "jfr" ? { outputFormat: "jfr" as const } : {}),
              },
            },
          }
        : {}),
    };
  } catch {
    return null;
  }
}
