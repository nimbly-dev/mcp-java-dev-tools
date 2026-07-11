/**
 * Performance plan execution support: manifest loading, request construction,
 * health checks, workload execution, and result aggregation remain owned by
 * the Performance Suite Feature Module.
 */
import path from "node:path";
import { promises as fs } from "node:fs";

import { buildTimestampRunId } from "@tools-feature-regression-suite";
import { resolvePlansRootAbs } from "../../../spec/regression-execution-plan-spec/src/regression_artifact_paths.util";
import { resolveProjectContextForRegression } from "@tools-feature-regression-suite";
import { buildPerformanceMstaSummary } from "../performance_msta_summary";
import {
  buildResolvedSecretRedactionMeta,
  sanitizeSuitePersistedContext,
} from "@tools-feature-regression-suite";
import { parsePerformancePlanMetadata } from "./parse_performance_plan_metadata";
import type { ExecutePerformancePlanWorkflowArgs } from "../models/performance_suite.model";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
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
  resolveProfilerStartFailure,
  buildPersistedMstaSummary,
  isProfilerDownloadSuccess,
  isProfilerDownloadNotReady,
} from "./parse_performance_contract";

async function readJsonFile(absPath: string): Promise<unknown> {
  const text = await fs.readFile(absPath, "utf8");
  return JSON.parse(text);
}

import { verifyHealthcheck } from "./performance_request_health";
import { executePerformanceWorkload } from "./performance_workload_execution";

export async function executePerformancePlanWorkflow(
  args: ExecutePerformancePlanWorkflowArgs,
): Promise<
  | {
      status: "blocked";
      preflight: { reasonCode: string; requiredUserAction: string[] };
      runId?: string;
      artifacts?: { runDirAbs: string };
    }
  | {
      status: "executed";
      runStatus: "pass" | "fail" | "blocked";
      runId: string;
      artifacts: { runDirAbs: string };
    }
> {
  const plansRootAbs = await resolvePlansRootAbs({
    workspaceRootAbs: args.workspaceRootAbs,
    projectName: args.projectName,
    suiteType: "performance",
  });
  const planRootAbs = path.join(plansRootAbs, args.planName);
  const metadataParsed = parsePerformancePlanMetadata(
    await readJsonFile(path.join(planRootAbs, "metadata.json")),
  );
  if (!metadataParsed.ok) {
    return buildBlockedResult({
      reasonCode: metadataParsed.reasonCode,
      requiredUserAction: metadataParsed.requiredUserAction,
    });
  }
  const contractParsed = parsePerformanceContract(
    await readJsonFile(path.join(planRootAbs, "contract.json")),
  );
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
  const persistedContext = sanitizeSuitePersistedContext(
    providedContext,
    explicitSecretPaths,
  ) as Record<string, unknown>;
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
      isRecord(reset.structuredContent.result) &&
      typeof reset.structuredContent.result.reasonCode === "string"
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
          ...(contract.analysis.executionTiming.event
            ? { event: contract.analysis.executionTiming.event }
            : {}),
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
  const workload = await executePerformanceWorkload({
    contract,
    entrypoint,
    providedContext,
    runId,
    runDirAbs,
    planName: args.planName,
    mcpInvoke: args.mcpInvoke,
    ...(args.runtimeConfigOverride ? { runtimeConfigOverride: args.runtimeConfigOverride } : {}),
  });
  const {
    totalRequests,
    failedRequests,
    latencies,
    transportBlockedReasonCode,
    transportBlockedMessage,
    workloadProviderArtifacts,
  } = workload;
  const endedAt = new Date();
  if (contract.analysis?.executionTiming?.enabled === true) {
    const profilerSessionId = `${runId}-execution-timing`;
    const profilerOutputPath = path.join(
      runDirAbs,
      `execution-timing.${contract.analysis.executionTiming.outputFormat ?? "jfr"}`,
    );
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
    const reasonCode =
      typeof probeResult.reasonCode === "string" ? probeResult.reasonCode : undefined;
    requiredLineHitResults.push({
      key: lineKey,
      hit,
      ...(reasonCode ? { reasonCode } : {}),
    });
    if (
      !hit &&
      reasonCode &&
      reasonCode !== "timeout_no_inline_hit" &&
      !strictLineBlockedReasonCode
    ) {
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
            ? {
                methodTargets: contract.analysis.msta.methodTargets.map((entry) => entry.methodRef),
              }
            : {}),
          ...(contract.analysis?.msta?.mode ? { mode: contract.analysis.msta.mode } : {}),
          ...(contract.analysis?.executionTiming
            ? {
                provider: {
                  name: contract.analysis.executionTiming.provider,
                  ...(contract.analysis.executionTiming.event
                    ? { event: contract.analysis.executionTiming.event }
                    : {}),
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
    strictLineBlockedReasonCode === undefined &&
    requiredLineHitResults.every((entry) => entry.hit === true);

  const runStatus: "pass" | "fail" | "blocked" =
    transportBlockedReasonCode || strictLineBlockedReasonCode
      ? "blocked"
      : thresholdResults.maxErrorRatePct.pass &&
          thresholdResults.minThroughputPerSec.pass &&
          thresholdResults.p95LatencyMs.pass &&
          requiredLineHitsPass
        ? "pass"
        : "fail";

  logPerformancePhase({
    runId,
    planName: args.planName,
    phase: "execution_result_write_begin",
    detail: runStatus,
  });
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
        ...(transportBlockedReasonCode
          ? { reasonCode: transportBlockedReasonCode, errorMessage: transportBlockedMessage }
          : {}),
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
