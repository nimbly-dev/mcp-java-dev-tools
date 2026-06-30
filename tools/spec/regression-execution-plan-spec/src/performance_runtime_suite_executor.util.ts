import path from "node:path";
import { promises as fs } from "node:fs";

import type { RuntimeSuiteManifest, RuntimeSuiteRunResult } from "@tools-regression-execution-plan-spec/models/regression_runtime_suite.model";
import { buildTimestampRunId } from "@tools-regression-execution-plan-spec/regression_execution_plan_spec.util";
import { deepResolvePlaceholderValue } from "@tools-regression-execution-plan-spec/placeholder_resolution.util";
import { resolvePlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";
import { resolveProjectContextForRegression } from "@tools-regression-execution-plan-spec/suite_project_context.util";
import {
  buildResolvedSecretRedactionMeta,
  sanitizeSuitePersistedContext,
} from "@tools-regression-execution-plan-spec/suite_context_redaction.util";
import {
  buildPerformanceMstaSummary,
  type PerformanceMstaSummary,
} from "@tools-regression-execution-plan-spec/performance_msta_summary.util";
import { readProjectArtifact } from "@tools-project-artifact-spec/project_artifact.util";
import {
  runJmeterGeneratedHttpWorkload,
  type JmeterWorkloadProvider,
  type PerformanceWorkloadProvider,
} from "@tools-performance-workload-jmeter/index";

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

function toIso(value: Date | null): string | null {
  return value ? value.toISOString() : null;
}

function delayMs(ms: number): Promise<void> {
  if (ms <= 0) return Promise.resolve();
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function logPerformancePhase(args: {
  runId: string;
  planName: string;
  phase: string;
  detail?: string;
}): void {
  const suffix = args.detail ? ` detail=${args.detail}` : "";
  console.error(
    `[perf-suite] ts=${new Date().toISOString()} runId=${args.runId} plan=${args.planName} phase=${args.phase}${suffix}`,
  );
}

function percentile(values: number[], ratio: number): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil(sorted.length * ratio) - 1));
  return sorted[index] ?? 0;
}

function buildBlockedResult(args: {
  reasonCode: string;
  requiredUserAction: string[];
  runId?: string;
  runDirAbs?: string;
}) {
  return {
    status: "blocked" as const,
    preflight: {
      reasonCode: args.reasonCode,
      requiredUserAction: args.requiredUserAction,
    },
    ...(typeof args.runId === "string" ? { runId: args.runId } : {}),
    ...(typeof args.runDirAbs === "string" ? { artifacts: { runDirAbs: args.runDirAbs } } : {}),
  };
}

type PerformanceEntrypoint = {
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

type PerformancePlanContract = {
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

type PersistedPerformanceMstaSummary =
  | PerformanceMstaSummary
  | {
      status: "not_configured" | "disabled";
    };

type PerformanceMstaConfigState = PersistedPerformanceMstaSummary["status"];

type PerformancePlanMetadata = {
  specVersion?: string;
  suiteType: "performance";
  execution: {
    intent: "performance";
  };
};

function parsePerformanceMetadata(
  input: unknown,
): { ok: true; metadata: PerformancePlanMetadata } | { ok: false; reasonCode: string; requiredUserAction: string[] } {
  if (!isRecord(input)) {
    return {
      ok: false,
      reasonCode: "performance_plan_invalid",
      requiredUserAction: ["Set metadata.json as an object for the performance plan."],
    };
  }
  const suiteType = asTrimmedString(input.suiteType);
  const execution = isRecord(input.execution) ? input.execution : null;
  const intent = execution ? asTrimmedString(execution.intent) : undefined;
  if (suiteType !== "performance" || intent !== "performance") {
    return {
      ok: false,
      reasonCode: "performance_plan_invalid",
      requiredUserAction: ["Set metadata.suiteType=performance and metadata.execution.intent=performance."],
    };
  }
  return {
    ok: true,
    metadata: {
      ...(typeof input.specVersion === "string" ? { specVersion: input.specVersion } : {}),
      suiteType: "performance",
      execution: { intent: "performance" },
    },
  };
}

function parseStringRecord(value: unknown): Record<string, string> | undefined {
  if (!isRecord(value)) return undefined;
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(value)) {
    const text = asTrimmedString(v);
    if (text) out[k] = text;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

function parsePerformanceContract(
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
  const workloadProvider = resolveWorkloadProvider(input);
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

function resolveWorkloadProvider(
  input: Record<string, unknown>,
): { ok: true; provider: PerformanceWorkloadProvider } | { ok: false; reasonCode: string; requiredUserAction: string[] } {
  const rawProvider = isRecord(input.workloadProvider) ? input.workloadProvider : null;
  if (!rawProvider) {
    return {
      ok: true,
      provider: { type: "builtin" },
    };
  }
  const type = asTrimmedString(rawProvider.type);
  if (type === "builtin") {
    return {
      ok: true,
      provider: { type: "builtin" },
    };
  }
  if (type !== "jmeter") {
    return {
      ok: false,
      reasonCode: "performance_workload_provider_invalid",
      requiredUserAction: ["Set workloadProvider.type to builtin or jmeter."],
    };
  }
  const mode = asTrimmedString(rawProvider.mode);
  if (mode !== "generated_http") {
    return {
      ok: false,
      reasonCode: "performance_workload_provider_invalid",
      requiredUserAction: ["Set workloadProvider.mode=generated_http when workloadProvider.type=jmeter."],
    };
  }
  const options = isRecord(rawProvider.options) ? rawProvider.options : null;
  const installationPath = asTrimmedString(options?.installationPath);
  return {
    ok: true,
    provider: {
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
    },
  };
}

function isProfilerDownloadSuccess(value: unknown): boolean {
  if (!isRecord(value)) return false;
  const result = isRecord(value.result) ? value.result : null;
  return result?.status === "downloaded";
}

function isProfilerDownloadNotReady(value: unknown): boolean {
  if (!isRecord(value)) return false;
  const response = isRecord(value.response) ? value.response : null;
  const responseJson = isRecord(response?.json) ? response?.json : null;
  return response?.status === 404 && responseJson?.error === "profiler_output_not_found";
}

function resolveExecutionTiming(
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

function resolveMsta(
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

function resolveRawMstaConfig(input: Record<string, unknown>): unknown {
  const analysis = isRecord(input.analysis) ? input.analysis : null;
  return analysis ? analysis.msta : undefined;
}

function validateMstaConfig(input: Record<string, unknown>): string[] | undefined {
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

function resolveMstaConfigState(input: Record<string, unknown>): PerformanceMstaConfigState {
  const rawMsta = resolveRawMstaConfig(input);
  if (typeof rawMsta === "undefined") return "not_configured";
  if (isRecord(rawMsta) && rawMsta.enabled === false) return "disabled";
  return "available";
}

function buildPersistedMstaSummary(args: {
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

async function readJsonFile(absPath: string): Promise<unknown> {
  const text = await fs.readFile(absPath, "utf8");
  return JSON.parse(text);
}

async function readPerformanceSuiteManifest(args: {
  workspaceRootAbs: string;
  projectName: string;
  executionProfile: string;
}): Promise<{ ok: true; manifest: RuntimeSuiteManifest & { suiteType: "performance" } } | { ok: false; reasonCode: string; requiredUserAction: string[] }> {
  const projectsFileAbs = path.join(args.workspaceRootAbs, ".mcpjvm", args.projectName, "projects.json");
  const parsed = await readProjectArtifact(projectsFileAbs).catch(() => ({
    ok: false as const,
    reasonCode: "project_artifact_missing" as const,
    errors: [`Create project artifact at ${projectsFileAbs}.`],
  }));
  if (!parsed.ok) {
    return {
      ok: false,
      reasonCode: parsed.reasonCode,
      requiredUserAction: parsed.errors,
    };
  }
  const workspace = parsed.artifact.workspaces.find((entry) => entry.projectRoot === args.workspaceRootAbs);
  if (!workspace) {
    return {
      ok: false,
      reasonCode: "runtime_suite_missing",
      requiredUserAction: ["Workspace entry not found for current projectRoot in projects.json."],
    };
  }
  const profiles = Array.isArray(workspace.executionProfiles) ? workspace.executionProfiles : [];
  const profile = profiles.find((entry) => entry.executionProfile === args.executionProfile);
  if (!profile) {
    return {
      ok: false,
      reasonCode: "runtime_suite_missing",
      requiredUserAction: [`Add executionProfiles entry '${args.executionProfile}' to projects.json.`],
    };
  }
  if (profile.suiteType !== "performance") {
    return {
      ok: false,
      reasonCode: "performance_profile_required",
      requiredUserAction: ["Set executionProfiles[].suiteType to performance for the selected execution profile."],
    };
  }
  return {
    ok: true,
    manifest: {
      executionProfile: profile.executionProfile,
      suiteType: "performance",
      ...(profile.runtimeContextName ? { runtimeContextName: profile.runtimeContextName } : {}),
      executionPolicy: profile.executionPolicy,
      ...(profile.runtimeConfig ? { runtimeConfig: profile.runtimeConfig } : {}),
      ...(profile.scriptRefs ? { scriptRefs: profile.scriptRefs } : {}),
      plans: profile.plans,
    },
  };
}

async function buildTransportRequest(args: {
  entrypoint: PerformanceEntrypoint;
  providedContext: Record<string, unknown>;
  requestTimeoutMs?: number;
}): Promise<{ request: Record<string, unknown>; wrappedOnly: boolean } | { error: string }> {
  try {
    const requestSpec = deepResolvePlaceholderValue(args.entrypoint.request, args.providedContext) as Record<string, unknown>;
    const transportSpec = deepResolvePlaceholderValue(args.entrypoint.transport, args.providedContext) as Record<string, unknown>;
    const baseUrl = asTrimmedString(transportSpec.baseUrl);
    const method = asTrimmedString(requestSpec.method);
    const requestPath = asTrimmedString(requestSpec.path);
    if (!baseUrl || !method || !requestPath) {
      return { error: "entrypoint transport baseUrl/method/path are required" };
    }
    const url = new URL(requestPath, baseUrl);
    const queryTemplate = isRecord(requestSpec.queryTemplate) ? requestSpec.queryTemplate : undefined;
    if (queryTemplate) {
      for (const [key, value] of Object.entries(queryTemplate)) {
        if (typeof value !== "undefined" && value !== null) {
          url.searchParams.set(key, String(value));
        }
      }
    }
    const headers = {
      ...(parseStringRecord(transportSpec.defaultHeaders) ?? {}),
      ...(parseStringRecord(requestSpec.headers) ?? {}),
    };
    return {
      request: {
        method,
        url: url.toString(),
        ...(Object.keys(headers).length > 0 ? { headers } : {}),
        ...(typeof requestSpec.body !== "undefined" ? { body: requestSpec.body } : {}),
        ...(typeof args.requestTimeoutMs === "number" ? { timeoutMs: args.requestTimeoutMs } : {}),
      },
      wrappedOnly: transportSpec.wrappedOnly !== false,
    };
  } catch (error) {
    return { error: error instanceof Error ? error.message : String(error) };
  }
}

async function verifyHealthcheck(args: {
  entrypoint: PerformanceEntrypoint;
  providedContext: Record<string, unknown>;
  requestTimeoutMs?: number;
  mcpInvoke: (args: { toolName: string; input: Record<string, unknown> }) => Promise<{ structuredContent: Record<string, unknown> }>;
}): Promise<{ ok: true } | { ok: false; reasonCode: string; requiredUserAction: string[] }> {
  const healthCheckPath = args.entrypoint.transport.healthCheckPath;
  if (!healthCheckPath) return { ok: true };
  const request = await buildTransportRequest({
    entrypoint: {
      ...args.entrypoint,
      request: {
        method: "GET",
        path: healthCheckPath,
      },
    },
    providedContext: args.providedContext,
    ...(typeof args.requestTimeoutMs === "number" ? { requestTimeoutMs: args.requestTimeoutMs } : {}),
  });
  if ("error" in request) {
    return {
      ok: false,
      reasonCode: "external_healthcheck_failed",
      requiredUserAction: [`Fix healthcheck request: ${request.error}`],
    };
  }
  const out = await args.mcpInvoke({
    toolName: "transport_execute",
    input: {
      request: request.request,
      wrappedOnly: request.wrappedOnly,
    },
  });
  if (out.structuredContent.status !== "pass") {
    return {
      ok: false,
      reasonCode: "external_healthcheck_failed",
      requiredUserAction: ["Ensure the performance target runtime healthcheck is reachable before execution."],
    };
  }
  return { ok: true };
}

type ExecutePerformancePlanWorkflowArgs = {
  workspaceRootAbs: string;
  projectName: string;
  planName: string;
  executionProfileName: string;
  suiteRunId: string;
  runtimeContextName?: string;
  runtimeConfigOverride?: {
    requestTimeoutMs?: number;
    retryMax?: number;
  };
  providedContext?: Record<string, unknown>;
  mcpInvoke: (args: { toolName: string; input: Record<string, unknown> }) => Promise<{ structuredContent: Record<string, unknown> }>;
};

async function executePerformancePlanWorkflow(
  args: ExecutePerformancePlanWorkflowArgs,
): Promise<
  | { status: "blocked"; preflight: { reasonCode: string; requiredUserAction: string[] }; runId?: string; artifacts?: { runDirAbs: string } }
  | { status: "executed"; runStatus: "pass" | "fail" | "blocked"; runId: string; artifacts: { runDirAbs: string } }
> {
  const plansRootAbs = await resolvePlansRootAbs({
    workspaceRootAbs: args.workspaceRootAbs,
    projectName: args.projectName,
    suiteType: "performance",
  });
  const planRootAbs = path.join(plansRootAbs, args.planName);
  const metadataParsed = parsePerformanceMetadata(await readJsonFile(path.join(planRootAbs, "metadata.json")));
  if (!metadataParsed.ok) {
    return buildBlockedResult({
      reasonCode: metadataParsed.reasonCode,
      requiredUserAction: metadataParsed.requiredUserAction,
    });
  }
  const contractParsed = parsePerformanceContract(await readJsonFile(path.join(planRootAbs, "contract.json")));
  if (!contractParsed.ok) {
    return buildBlockedResult({
      reasonCode: contractParsed.reasonCode,
      requiredUserAction: contractParsed.requiredUserAction,
    });
  }

  const contract = contractParsed.contract;
  const mstaConfigState = contractParsed.mstaConfigState;
  const entrypoint = contract.entrypoints[0]!;
  const runId = buildTimestampRunId(new Date(), 1);
  const runDirAbs = path.join(planRootAbs, "runs", runId);
  logPerformancePhase({ runId, planName: args.planName, phase: "run_dir_create_begin" });
  await fs.mkdir(runDirAbs, { recursive: true });
  logPerformancePhase({ runId, planName: args.planName, phase: "run_dir_create_complete" });
  const projectContext = await resolveProjectContextForRegression({
    workspaceRootAbs: args.workspaceRootAbs,
    projectsFileAbs: path.join(args.workspaceRootAbs, ".mcpjvm", args.projectName, "projects.json"),
    executionProfileName: args.executionProfileName,
    ...(args.runtimeContextName ? { runtimeContextName: args.runtimeContextName } : {}),
    ...(args.runtimeConfigOverride ? { defaultsOverride: args.runtimeConfigOverride } : {}),
    strictProbeVerification: true,
  });
  if (projectContext.status === "blocked") {
    await fs.writeFile(
      path.join(runDirAbs, "execution.result.json"),
      `${JSON.stringify(
        {
          status: "blocked",
          startedAt: null,
          endedAt: null,
          reasonCode: projectContext.reasonCode,
          ...(projectContext.checks ? { checks: projectContext.checks } : {}),
          ...(projectContext.nextAction ? { nextAction: projectContext.nextAction } : {}),
          requiredUserAction: projectContext.requiredUserAction,
        },
        null,
        2,
      )}\n`,
      "utf8",
    );
    await fs.writeFile(
      path.join(runDirAbs, "evidence.json"),
      `${JSON.stringify(
        {
          entrypoint,
          observationTargets: contract.observationTargets,
          projectContext: {
            reasonCode: projectContext.reasonCode,
            ...(projectContext.checks ? { checks: projectContext.checks } : {}),
            ...(projectContext.nextAction ? { nextAction: projectContext.nextAction } : {}),
            requiredUserAction: projectContext.requiredUserAction,
          },
        },
        null,
        2,
      )}\n`,
      "utf8",
    );
    return buildBlockedResult({
      reasonCode: projectContext.reasonCode,
      requiredUserAction: projectContext.requiredUserAction,
      runId,
      runDirAbs,
    });
  }
  const providedContext = {
    ...projectContext.contextPatch,
    ...(args.providedContext ?? {}),
  };
  const explicitSecretPaths = new Set(projectContext.secretContextKeys);
  const persistedContext = sanitizeSuitePersistedContext(providedContext, explicitSecretPaths) as Record<string, unknown>;
  const resolvedSecretRedactionMeta = buildResolvedSecretRedactionMeta({
    resolvedContext: providedContext,
    explicitSecretPaths,
  });
  logPerformancePhase({ runId, planName: args.planName, phase: "context_write_begin" });
  await fs.writeFile(
    path.join(runDirAbs, "context.resolved.json"),
    `${JSON.stringify(
      {
        resolvedAt: new Date().toISOString(),
        executionProfile: args.executionProfileName,
        suiteRunId: args.suiteRunId,
        ...(resolvedSecretRedactionMeta ? { redaction: resolvedSecretRedactionMeta } : {}),
        providedContext: persistedContext,
      },
      null,
      2,
    )}\n`,
    "utf8",
  );
  logPerformancePhase({ runId, planName: args.planName, phase: "context_write_complete" });

  logPerformancePhase({ runId, planName: args.planName, phase: "healthcheck_begin" });
  const health = await verifyHealthcheck({
    entrypoint,
    providedContext,
    ...(typeof args.runtimeConfigOverride?.requestTimeoutMs === "number"
      ? { requestTimeoutMs: args.runtimeConfigOverride.requestTimeoutMs }
      : {}),
    mcpInvoke: args.mcpInvoke,
  });
  logPerformancePhase({
    runId,
    planName: args.planName,
    phase: "healthcheck_complete",
    detail: health.ok ? "ok" : health.reasonCode,
  });
  if (!health.ok) {
    await fs.writeFile(
      path.join(runDirAbs, "execution.result.json"),
      `${JSON.stringify(
        {
          status: "blocked",
          startedAt: null,
          endedAt: null,
          reasonCode: health.reasonCode,
          requiredUserAction: health.requiredUserAction,
        },
        null,
        2,
      )}\n`,
      "utf8",
    );
    await fs.writeFile(
      path.join(runDirAbs, "evidence.json"),
      `${JSON.stringify(
        {
          entrypoint,
          observationTargets: contract.observationTargets,
        },
        null,
        2,
      )}\n`,
      "utf8",
    );
    return buildBlockedResult({
      reasonCode: health.reasonCode,
      requiredUserAction: health.requiredUserAction,
      runId,
      runDirAbs,
    });
  }

  for (const lineKey of contract.observationTargets.requiredLineHits) {
    const resetInput: Record<string, unknown> = { key: lineKey };
    if (typeof contract.observationTargets.probeId === "string") {
      resetInput.probeId = contract.observationTargets.probeId;
    }
    const reset = await args.mcpInvoke({
      toolName: "probe",
      input: { action: "reset", input: resetInput },
    });
    const resetReasonCode =
      isRecord(reset.structuredContent.result) && typeof reset.structuredContent.result.reasonCode === "string"
        ? reset.structuredContent.result.reasonCode
        : undefined;
    if (resetReasonCode && resetReasonCode !== "ok") {
      await fs.writeFile(
        path.join(runDirAbs, "execution.result.json"),
        `${JSON.stringify(
          {
            status: "blocked",
            startedAt: null,
            endedAt: null,
            reasonCode: resetReasonCode,
            requiredUserAction: ["Align the runtime probe target and rerun the performance suite."],
          },
          null,
          2,
        )}\n`,
        "utf8",
      );
      await fs.writeFile(
        path.join(runDirAbs, "evidence.json"),
        `${JSON.stringify(
          {
            entrypoint,
            observationTargets: contract.observationTargets,
          },
          null,
          2,
        )}\n`,
        "utf8",
      );
      return buildBlockedResult({
        reasonCode: resetReasonCode,
        requiredUserAction: ["Align the runtime probe target and rerun the performance suite."],
        runId,
        runDirAbs,
      });
    }
  }

  const startedAt = new Date();
  let profilerStartResult: Record<string, unknown> | undefined;
  let profilerStopResult: Record<string, unknown> | undefined;
  let workloadProviderArtifacts:
    | {
        jmxPathAbs?: string;
        jtlPathAbs?: string;
        logPathAbs?: string;
      }
    | undefined;
  if (contract.analysis?.executionTiming?.enabled === true) {
    const profilerSessionId = `${runId}-execution-timing`;
    logPerformancePhase({ runId, planName: args.planName, phase: "profiler_start_begin" });
    const profilerStart = await args.mcpInvoke({
      toolName: "probe",
      input: {
        action: "profiler",
        input: {
          action: "start",
          sessionId: profilerSessionId,
          ...(typeof contract.observationTargets.probeId === "string"
            ? { probeId: contract.observationTargets.probeId }
            : {}),
          ...(contract.analysis.executionTiming.event ? { event: contract.analysis.executionTiming.event } : {}),
          ...(typeof contract.analysis.executionTiming.intervalNanos === "number"
            ? { intervalNanos: contract.analysis.executionTiming.intervalNanos }
            : {}),
          outputFormat: contract.analysis.executionTiming.outputFormat ?? "jfr",
        },
      },
    });
    profilerStartResult = profilerStart.structuredContent;
    logPerformancePhase({ runId, planName: args.planName, phase: "profiler_start_complete" });
  }
  const deadlineEpochMs = Date.now() + contract.loadModel.durationSeconds * 1000;
  const latencies: number[] = [];
  let totalRequests = 0;
  let failedRequests = 0;
  let transportBlockedReasonCode: string | undefined;
  let transportBlockedMessage: string | undefined;
  logPerformancePhase({ runId, planName: args.planName, phase: "workload_begin" });
  if (contract.workloadProvider.type === "jmeter") {
    const resolved = await buildTransportRequest({
      entrypoint,
      providedContext,
      ...(typeof args.runtimeConfigOverride?.requestTimeoutMs === "number"
        ? { requestTimeoutMs: args.runtimeConfigOverride.requestTimeoutMs }
        : {}),
    });
    if ("error" in resolved) {
      transportBlockedReasonCode = "performance_plan_invalid";
      transportBlockedMessage = resolved.error;
    } else {
      const jmeterRequest: {
        method: string;
        url: string;
        headers?: Record<string, string>;
        body?: unknown;
        timeoutMs?: number;
      } = {
        method: String(resolved.request.method ?? "GET"),
        url: String(resolved.request.url ?? ""),
      };
      const resolvedHeaders = isRecord(resolved.request.headers) ? parseStringRecord(resolved.request.headers) : undefined;
      if (resolvedHeaders) {
        jmeterRequest.headers = resolvedHeaders;
      }
      if ("body" in resolved.request) {
        jmeterRequest.body = resolved.request.body;
      }
      if (typeof resolved.request.timeoutMs === "number") {
        jmeterRequest.timeoutMs = resolved.request.timeoutMs;
      }
      const jmeterResult = await runJmeterGeneratedHttpWorkload({
        provider: contract.workloadProvider as JmeterWorkloadProvider,
        request: jmeterRequest,
        loadModel: contract.loadModel,
        runDirAbs,
        planName: args.planName,
      });
      workloadProviderArtifacts = jmeterResult.artifacts;
      if (jmeterResult.status === "blocked") {
        transportBlockedReasonCode = jmeterResult.reasonCode;
        transportBlockedMessage = jmeterResult.requiredUserAction.join(" ");
      } else {
        totalRequests = jmeterResult.metrics.totalRequests;
        failedRequests = jmeterResult.metrics.failedRequests;
        latencies.push(...jmeterResult.metrics.latenciesMs);
      }
    }
  } else {
    const worker = async () => {
      while (Date.now() < deadlineEpochMs && !transportBlockedReasonCode) {
        const resolved = await buildTransportRequest({
          entrypoint,
          providedContext,
          ...(typeof args.runtimeConfigOverride?.requestTimeoutMs === "number"
            ? { requestTimeoutMs: args.runtimeConfigOverride.requestTimeoutMs }
            : {}),
        });
        if ("error" in resolved) {
          transportBlockedReasonCode = "performance_plan_invalid";
          transportBlockedMessage = resolved.error;
          return;
        }
        const out = await args.mcpInvoke({
          toolName: "transport_execute",
          input: {
            request: resolved.request,
            wrappedOnly: resolved.wrappedOnly,
          },
        });
        const status = asTrimmedString(out.structuredContent.status);
        const durationMs =
          typeof out.structuredContent.durationMs === "number" ? Math.max(1, Math.round(out.structuredContent.durationMs)) : 1;
        if (status === "pass" || status === "fail_http") {
          totalRequests += 1;
          latencies.push(durationMs);
          if (status !== "pass") failedRequests += 1;
          continue;
        }
        transportBlockedReasonCode =
          typeof out.structuredContent.reasonCode === "string" ? out.structuredContent.reasonCode : "transport_request_failed";
        transportBlockedMessage =
          typeof out.structuredContent.errorMessage === "string"
            ? out.structuredContent.errorMessage
            : "performance transport execution failed";
        return;
      }
    };

    const perWorkerDelayMs =
      contract.loadModel.concurrency > 1
        ? Math.floor((contract.loadModel.rampUpSeconds * 1000) / contract.loadModel.concurrency)
        : 0;
    await Promise.all(
      Array.from({ length: contract.loadModel.concurrency }, (_value, index) =>
        (async () => {
          if (perWorkerDelayMs > 0 && index > 0) {
            await delayMs(perWorkerDelayMs * index);
          }
          await worker();
        })(),
      ),
    );
  }
  logPerformancePhase({
    runId,
    planName: args.planName,
    phase: "workload_complete",
    detail: `requests=${totalRequests} failures=${failedRequests}${transportBlockedReasonCode ? ` blocked=${transportBlockedReasonCode}` : ""}`,
  });
  const endedAt = new Date();
  if (contract.analysis?.executionTiming?.enabled === true) {
    const profilerSessionId = `${runId}-execution-timing`;
    const profilerOutputPath = path.join(runDirAbs, `execution-timing.${contract.analysis.executionTiming.outputFormat ?? "jfr"}`);
    logPerformancePhase({ runId, planName: args.planName, phase: "profiler_stop_begin" });
    const profilerStop = await args.mcpInvoke({
      toolName: "probe",
      input: {
        action: "profiler",
        input: {
          action: "stop",
          sessionId: profilerSessionId,
          ...(typeof contract.observationTargets.probeId === "string"
            ? { probeId: contract.observationTargets.probeId }
            : {}),
          outputFormat: contract.analysis.executionTiming.outputFormat ?? "jfr",
        },
      },
    });
    profilerStopResult = profilerStop.structuredContent;
    logPerformancePhase({ runId, planName: args.planName, phase: "profiler_stop_complete" });
    logPerformancePhase({ runId, planName: args.planName, phase: "profiler_download_begin" });
    let profilerDownload = await args.mcpInvoke({
      toolName: "probe",
      input: {
        action: "profiler",
        input: {
          action: "download",
          sessionId: profilerSessionId,
          ...(typeof contract.observationTargets.probeId === "string"
            ? { probeId: contract.observationTargets.probeId }
            : {}),
          outputPath: profilerOutputPath,
          outputFormat: contract.analysis.executionTiming.outputFormat ?? "jfr",
        },
      },
    });
    for (let attempt = 0; attempt < 10; attempt += 1) {
      if (isProfilerDownloadSuccess(profilerDownload.structuredContent)) break;
      if (!isProfilerDownloadNotReady(profilerDownload.structuredContent)) break;
      await delayMs(250);
      profilerDownload = await args.mcpInvoke({
        toolName: "probe",
        input: {
          action: "profiler",
          input: {
            action: "download",
            sessionId: profilerSessionId,
            ...(typeof contract.observationTargets.probeId === "string"
              ? { probeId: contract.observationTargets.probeId }
              : {}),
            outputPath: profilerOutputPath,
            outputFormat: contract.analysis.executionTiming.outputFormat ?? "jfr",
          },
        },
      });
    }
    logPerformancePhase({ runId, planName: args.planName, phase: "profiler_download_complete" });
    if (isRecord(profilerStopResult)) {
      profilerStopResult.download = profilerDownload.structuredContent;
    }
  }

  const requiredLineHitResults: Array<{ key: string; hit: boolean; reasonCode?: string }> = [];
  let strictLineBlockedReasonCode: string | undefined;
  logPerformancePhase({ runId, planName: args.planName, phase: "strict_wait_begin" });
  for (const lineKey of contract.observationTargets.requiredLineHits) {
    const waitInput: Record<string, unknown> = {
      key: lineKey,
      timeoutMs: Math.max(1000, contract.loadModel.durationSeconds * 1000),
    };
    if (typeof contract.observationTargets.probeId === "string") {
      waitInput.probeId = contract.observationTargets.probeId;
    }
    const out = await args.mcpInvoke({
      toolName: "probe",
      input: {
        action: "wait_for_hit",
        input: waitInput,
      },
    });
    const probeResult = isRecord(out.structuredContent.result) ? out.structuredContent.result : {};
    const hit = probeResult.hit === true;
    const reasonCode = typeof probeResult.reasonCode === "string" ? probeResult.reasonCode : undefined;
    requiredLineHitResults.push({
      key: lineKey,
      hit,
      ...(reasonCode ? { reasonCode } : {}),
    });
    if (!hit && reasonCode && reasonCode !== "timeout_no_inline_hit" && !strictLineBlockedReasonCode) {
      strictLineBlockedReasonCode = reasonCode;
    }
  }
  logPerformancePhase({
    runId,
    planName: args.planName,
    phase: "strict_wait_complete",
    detail: strictLineBlockedReasonCode ?? "ok",
  });

  const totalDurationMs = Math.max(1, endedAt.getTime() - startedAt.getTime());
  logPerformancePhase({ runId, planName: args.planName, phase: "msta_begin" });
  const materializedMstaSummary =
    contract.analysis?.executionTiming?.enabled === true
      ? await buildPerformanceMstaSummary({
          requiredLineHits: contract.observationTargets.requiredLineHits,
          ...(contract.analysis?.msta?.methodTargets
            ? { methodTargets: contract.analysis.msta.methodTargets.map((entry) => entry.methodRef) }
            : {}),
          ...(contract.analysis?.msta?.mode ? { mode: contract.analysis.msta.mode } : {}),
          ...(contract.analysis?.executionTiming
            ? {
                provider: {
                  name: contract.analysis.executionTiming.provider,
                  ...(contract.analysis.executionTiming.event ? { event: contract.analysis.executionTiming.event } : {}),
                  ...(contract.analysis.executionTiming.outputFormat
                    ? { outputFormat: contract.analysis.executionTiming.outputFormat }
                    : {}),
                },
              }
            : {}),
          durationMs: totalDurationMs,
          ...(profilerStopResult ? { profilerStopResult } : {}),
          runDirAbs,
        })
      : undefined;
  const mstaSummary = buildPersistedMstaSummary({
    mstaConfigState,
    ...(materializedMstaSummary ? { materializedSummary: materializedMstaSummary } : {}),
  });
  logPerformancePhase({
    runId,
    planName: args.planName,
    phase: "msta_complete",
    detail: mstaSummary.status,
  });
  const throughputPerSec = totalRequests / (totalDurationMs / 1000);
  const errorRatePct = totalRequests > 0 ? (failedRequests / totalRequests) * 100 : 100;
  const p95LatencyMs = percentile(latencies, 0.95);
  const thresholdResults = {
    maxErrorRatePct: {
      actual: Number(errorRatePct.toFixed(3)),
      expectedMax: contract.successCriteria.maxErrorRatePct,
      pass: errorRatePct <= contract.successCriteria.maxErrorRatePct,
    },
    minThroughputPerSec: {
      actual: Number(throughputPerSec.toFixed(3)),
      expectedMin: contract.successCriteria.minThroughputPerSec,
      pass: throughputPerSec >= contract.successCriteria.minThroughputPerSec,
    },
    p95LatencyMs: {
      actual: p95LatencyMs,
      expectedMax: contract.successCriteria.p95LatencyMs,
      pass: p95LatencyMs <= contract.successCriteria.p95LatencyMs,
    },
  };

  const requiredLineHitsPass =
    strictLineBlockedReasonCode === undefined && requiredLineHitResults.every((entry) => entry.hit === true);

  const runStatus: "pass" | "fail" | "blocked" =
    transportBlockedReasonCode || strictLineBlockedReasonCode
      ? "blocked"
      : thresholdResults.maxErrorRatePct.pass &&
          thresholdResults.minThroughputPerSec.pass &&
          thresholdResults.p95LatencyMs.pass &&
          requiredLineHitsPass
        ? "pass"
        : "fail";

  logPerformancePhase({ runId, planName: args.planName, phase: "execution_result_write_begin", detail: runStatus });
  await fs.writeFile(
    path.join(runDirAbs, "execution.result.json"),
    `${JSON.stringify(
      {
        status: runStatus,
        startedAt: toIso(startedAt),
        endedAt: toIso(endedAt),
        metrics: {
          totalRequests,
          failedRequests,
          errorRatePct: Number(errorRatePct.toFixed(3)),
          throughputPerSec: Number(throughputPerSec.toFixed(3)),
          p95LatencyMs,
          durationMs: totalDurationMs,
        },
        thresholdResults,
        requiredLineHits: requiredLineHitResults,
        workloadProvider: contract.workloadProvider,
        ...(workloadProviderArtifacts ? { workloadProviderArtifacts } : {}),
        ...(profilerStopResult ? { executionTiming: profilerStopResult } : {}),
        msta: mstaSummary,
        ...(transportBlockedReasonCode ? { reasonCode: transportBlockedReasonCode, errorMessage: transportBlockedMessage } : {}),
        ...(strictLineBlockedReasonCode ? { reasonCode: strictLineBlockedReasonCode } : {}),
      },
      null,
      2,
    )}\n`,
    "utf8",
  );
  logPerformancePhase({ runId, planName: args.planName, phase: "execution_result_write_complete" });
  logPerformancePhase({ runId, planName: args.planName, phase: "evidence_write_begin" });
  await fs.writeFile(
    path.join(runDirAbs, "evidence.json"),
    `${JSON.stringify(
      {
        entrypoint,
        workloadProvider: contract.workloadProvider,
        observationTargets: contract.observationTargets,
        loadModel: contract.loadModel,
        successCriteria: contract.successCriteria,
        ...(workloadProviderArtifacts ? { workloadProviderArtifacts } : {}),
        requiredLineHits: requiredLineHitResults,
        ...(contract.analysis ? { analysis: contract.analysis } : {}),
        ...(profilerStartResult ? { profilerStart: profilerStartResult } : {}),
        ...(profilerStopResult ? { profilerStop: profilerStopResult } : {}),
        msta: mstaSummary,
      },
      null,
      2,
    )}\n`,
    "utf8",
  );
  logPerformancePhase({ runId, planName: args.planName, phase: "evidence_write_complete" });
  if (
    mstaSummary.status === "available" ||
    mstaSummary.status === "jfr_missing" ||
    mstaSummary.status === "jfr_parse_failed" ||
    mstaSummary.status === "no_anchor_samples"
  ) {
    logPerformancePhase({ runId, planName: args.planName, phase: "msta_artifact_write_begin" });
    await fs.writeFile(
      path.join(runDirAbs, "execution-timing.msta.json"),
      `${JSON.stringify(mstaSummary, null, 2)}\n`,
      "utf8",
    );
    logPerformancePhase({ runId, planName: args.planName, phase: "msta_artifact_write_complete" });
  }

  return {
    status: "executed",
    runStatus,
    runId,
    artifacts: { runDirAbs },
  };
}

export type ExecutePerformanceRuntimeSuiteArgs = {
  workspaceRootAbs: string;
  projectName: string;
  executionProfile: string;
  mcpInvoke: ExecutePerformancePlanWorkflowArgs["mcpInvoke"];
  suiteRunId?: string;
  startPlanOrder?: number;
  priorPlanRuns?: RuntimeSuiteRunResult["planRuns"];
  maxPlansPerCall?: number;
};

export async function executePerformanceRuntimeSuite(
  args: ExecutePerformanceRuntimeSuiteArgs,
): Promise<RuntimeSuiteRunResult | { status: "blocked"; reasonCode: string; requiredUserAction: string[] }> {
  const suite = await readPerformanceSuiteManifest({
    workspaceRootAbs: args.workspaceRootAbs,
    projectName: args.projectName,
    executionProfile: args.executionProfile,
  });
  if (!suite.ok) {
    return {
      status: "blocked",
      reasonCode: suite.reasonCode,
      requiredUserAction: suite.requiredUserAction,
    };
  }
  const manifest = suite.manifest;
  const suiteRunId =
    typeof args.suiteRunId === "string" && args.suiteRunId.trim().length > 0
      ? args.suiteRunId.trim()
      : buildTimestampRunId(new Date(), 1);
  const planRuns: RuntimeSuiteRunResult["planRuns"] = Array.isArray(args.priorPlanRuns)
    ? args.priorPlanRuns.map((entry) => ({ ...entry }))
    : [];
  let hasFail = planRuns.some((entry) => entry.status === "executed" && entry.runStatus === "fail");
  let hasBlocked = planRuns.some(
    (entry) => entry.status === "blocked" || (entry.status === "executed" && entry.runStatus === "blocked"),
  );
  const orderedPlans = [...manifest.plans].sort((a, b) => a.order - b.order);
  const startPlanOrder =
    typeof args.startPlanOrder === "number" && Number.isInteger(args.startPlanOrder) && args.startPlanOrder > 0
      ? args.startPlanOrder
      : 1;
  if (startPlanOrder > orderedPlans.length + 1) {
    return {
      status: "blocked",
      reasonCode: "suite_progress_invalid",
      requiredUserAction: [`Persist nextPlanOrder within 1..${orderedPlans.length + 1} before resuming suite execution.`],
    };
  }
  const maxPlansPerCall =
    typeof args.maxPlansPerCall === "number" && Number.isInteger(args.maxPlansPerCall) && args.maxPlansPerCall > 0
      ? args.maxPlansPerCall
      : undefined;
  let processedPlansThisCall = 0;
  let nextPlanOrder: number | undefined;
  let stop = false;

  for (const plan of orderedPlans) {
    if (plan.order < startPlanOrder) continue;
    if (stop) {
      planRuns.push({
        order: plan.order,
        planName: plan.planName,
        status: "skipped",
      });
      continue;
    }
    if (typeof maxPlansPerCall === "number" && processedPlansThisCall >= maxPlansPerCall) {
      nextPlanOrder = plan.order;
      break;
    }
    const run = await executePerformancePlanWorkflow({
      workspaceRootAbs: args.workspaceRootAbs,
      projectName: args.projectName,
      planName: plan.planName,
      executionProfileName: manifest.executionProfile,
      suiteRunId,
      ...(manifest.runtimeContextName ? { runtimeContextName: manifest.runtimeContextName } : {}),
      ...(manifest.runtimeConfig ? { runtimeConfigOverride: manifest.runtimeConfig } : {}),
      ...(plan.providedContext ? { providedContext: plan.providedContext } : {}),
      mcpInvoke: args.mcpInvoke,
    });
    processedPlansThisCall += 1;
    if (run.status === "blocked") {
      hasBlocked = true;
      planRuns.push({
        order: plan.order,
        planName: plan.planName,
        status: "blocked",
        blockedReasonCode: run.preflight.reasonCode,
        ...(typeof run.runId === "string" ? { runId: run.runId } : {}),
      });
      const effectiveOnFail =
        plan.onFail === "stop" || plan.onFail === "continue"
          ? plan.onFail
          : manifest.executionPolicy === "stop_on_fail"
            ? "stop"
            : "continue";
      if (effectiveOnFail === "stop") stop = true;
      continue;
    }
    planRuns.push({
      order: plan.order,
      planName: plan.planName,
      status: "executed",
      runStatus: run.runStatus,
      runId: run.runId,
    });
    if (run.runStatus === "fail") hasFail = true;
    if (run.runStatus === "blocked") hasBlocked = true;
    const effectiveOnFail =
      plan.onFail === "stop" || plan.onFail === "continue"
        ? plan.onFail
        : manifest.executionPolicy === "stop_on_fail"
          ? "stop"
          : "continue";
    if ((run.runStatus === "fail" || run.runStatus === "blocked") && effectiveOnFail === "stop") {
      stop = true;
    }
  }

  if (typeof nextPlanOrder === "number" && processedPlansThisCall === 0) {
    return {
      status: "blocked",
      reasonCode: "suite_progress_stalled",
      requiredUserAction: ["Advance at least one plan per resumed call or increase maxPlansPerCall."],
    };
  }

  if (typeof nextPlanOrder === "number") {
    return {
      executionProfile: manifest.executionProfile,
      executionPolicy: manifest.executionPolicy,
      status: "in_progress",
      planRuns,
      suiteRunId,
      nextPlanOrder,
      completedPlanCount: planRuns.length,
    };
  }

  let status: RuntimeSuiteRunResult["status"] = "pass";
  if (manifest.executionPolicy === "continue_on_fail") {
    if (hasBlocked || hasFail) status = "partial_fail";
  } else if (hasBlocked) {
    status = "blocked";
  } else if (hasFail) {
    status = "fail";
  }
  return {
    executionProfile: manifest.executionProfile,
    executionPolicy: manifest.executionPolicy,
    status,
    planRuns,
    suiteRunId,
    completedPlanCount: planRuns.length,
  };
}
