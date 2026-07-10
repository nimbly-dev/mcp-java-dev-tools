import path from "node:path";
import { promises as fs } from "node:fs";

import type { RuntimeSuiteManifest, RuntimeSuiteRunResult } from "../../../spec/regression-execution-plan-spec/src/models/regression_runtime_suite.model";
import { buildTimestampRunId } from "../../../spec/regression-execution-plan-spec/src/regression_execution_plan_spec.util";
import { deepResolvePlaceholderValue } from "../../../spec/regression-execution-plan-spec/src/placeholder_resolution.util";
import { resolvePlansRootAbs } from "../../../spec/regression-execution-plan-spec/src/regression_artifact_paths.util";
import { resolveProjectContextForRegression } from "@tools-feature-regression-suite";
import {
  buildResolvedSecretRedactionMeta,
  sanitizeSuitePersistedContext,
} from "@tools-feature-regression-suite";
import {
  buildPerformanceMstaSummary,
  type PerformanceMstaSummary,
} from "../performance_msta_summary";
import { readProjectArtifact } from "@tools-project-artifact-spec/project_artifact.util";
import {
  runJmeterGeneratedHttpWorkload,
  type JmeterWorkloadProvider,
  type PerformanceWorkloadProvider,
} from "../../performance-workload-jmeter/index";
import { resolvePerformanceWorkloadProvider } from "../support/resolve_performance_workload_provider";
import {
  parsePerformancePlanMetadata,
  type PerformancePlanMetadata,
} from "../support/parse_performance_plan_metadata";

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

import {
  parsePerformanceContract,
  parseStringRecord,
  resolveProfilerStartFailure,
  buildPersistedMstaSummary,
  isProfilerDownloadSuccess,
  isProfilerDownloadNotReady,
  type PerformanceEntrypoint,
  type PerformancePlanContract,
} from "../support/parse_performance_contract";

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
  const metadataParsed = parsePerformancePlanMetadata(await readJsonFile(path.join(planRootAbs, "metadata.json")));
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
    const profilerStartFailure = resolveProfilerStartFailure(profilerStartResult);
    if (profilerStartFailure) {
      const blockedMstaSummary = buildPersistedMstaSummary({ mstaConfigState });
      await fs.writeFile(
        path.join(runDirAbs, "execution.result.json"),
        `${JSON.stringify(
          {
            status: "blocked",
            reasonCode: profilerStartFailure.reasonCode,
            errorMessage: profilerStartFailure.detail,
            executionTiming: profilerStartResult,
            msta: blockedMstaSummary,
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
            loadModel: contract.loadModel,
            successCriteria: contract.successCriteria,
            ...(contract.analysis ? { analysis: contract.analysis } : {}),
            profilerStart: profilerStartResult,
            msta: blockedMstaSummary,
          },
          null,
          2,
        )}\n`,
        "utf8",
      );
      return buildBlockedResult({
        reasonCode: profilerStartFailure.reasonCode,
        requiredUserAction: [
          "Run the Probe on a profiler-supported JVM runtime or disable analysis.executionTiming / analysis.msta for this plan.",
        ],
        runId,
        runDirAbs,
      });
    }
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
