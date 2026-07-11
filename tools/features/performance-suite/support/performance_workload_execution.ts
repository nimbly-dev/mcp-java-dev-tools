/**
 * Executes the performance workload and returns normalized workload metrics.
 */
import type {
  ExecutePerformancePlanWorkflowArgs,
  PerformancePlanContract,
} from "../models/performance_suite.model";
import type { PerformanceEntrypoint } from "./parse_performance_contract";
import { parseStringRecord } from "./parse_performance_contract";
import {
  dispatchPerformanceWorkloadJmeterAction,
  type JmeterWorkloadProvider,
} from "../../performance-workload-jmeter/index";
import { buildTransportRequest } from "./performance_request_health";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function asTrimmedString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value.trim() : undefined;
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

export async function executePerformanceWorkload(args: {
  contract: PerformancePlanContract;
  entrypoint: PerformanceEntrypoint;
  providedContext: Record<string, unknown>;
  runId: string;
  runDirAbs: string;
  planName: string;
  mcpInvoke: ExecutePerformancePlanWorkflowArgs["mcpInvoke"];
  runtimeConfigOverride?: ExecutePerformancePlanWorkflowArgs["runtimeConfigOverride"];
}): Promise<{
  totalRequests: number;
  failedRequests: number;
  latencies: number[];
  transportBlockedReasonCode: string | undefined;
  transportBlockedMessage: string | undefined;
  workloadProviderArtifacts:
    { jmxPathAbs?: string; jtlPathAbs?: string; logPathAbs?: string } | undefined;
}> {
  const { contract, entrypoint, providedContext, runId, runDirAbs } = args;
  let workloadProviderArtifacts:
    { jmxPathAbs?: string; jtlPathAbs?: string; logPathAbs?: string } | undefined;
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
      const resolvedHeaders = isRecord(resolved.request.headers)
        ? parseStringRecord(resolved.request.headers)
        : undefined;
      if (resolvedHeaders) {
        jmeterRequest.headers = resolvedHeaders;
      }
      if ("body" in resolved.request) {
        jmeterRequest.body = resolved.request.body;
      }
      if (typeof resolved.request.timeoutMs === "number") {
        jmeterRequest.timeoutMs = resolved.request.timeoutMs;
      }
      const jmeterResult = await dispatchPerformanceWorkloadJmeterAction({
        action: "execute",
        input: {
          provider: contract.workloadProvider as JmeterWorkloadProvider,
          request: jmeterRequest,
          loadModel: contract.loadModel,
          runDirAbs,
          planName: args.planName,
        },
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
          typeof out.structuredContent.durationMs === "number"
            ? Math.max(1, Math.round(out.structuredContent.durationMs))
            : 1;
        if (status === "pass" || status === "fail_http") {
          totalRequests += 1;
          latencies.push(durationMs);
          if (status !== "pass") failedRequests += 1;
          continue;
        }
        transportBlockedReasonCode =
          typeof out.structuredContent.reasonCode === "string"
            ? out.structuredContent.reasonCode
            : "transport_request_failed";
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
  return {
    totalRequests,
    failedRequests,
    latencies,
    transportBlockedReasonCode,
    transportBlockedMessage,
    workloadProviderArtifacts,
  };
}
