import type {
  PlanContract,
  PlanWatcher,
} from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";
import type {
  RegressionRunExecutionResult,
  RegressionRunStatus,
  RegressionRunWatcherAttempt,
  RegressionRunWatcherOutcome,
  RegressionRunWatcherResult,
  RegressionWatcherPhaseStatus,
  RegressionRunStepResult,
} from "@tools-regression-execution-plan-spec/models/regression_run_artifact.model";
import type { TransportAdapter, TransportProtocol, TransportExecutionResult } from "@tools-regression-execution-plan-spec/models/regression_transport.model";
import { deepResolvePlaceholderValue } from "@tools-regression-execution-plan-spec/placeholder_resolution.util";
import { resolveWatcherWaitPolicy } from "@tools-regression-execution-plan-spec/regression_execution_plan_spec.util";
import {
  evaluateStepExpectations,
} from "@tools-regression-execution-plan-spec/regression_expectation_evaluator.util";
import { buildHttpPayload } from "@tools-regression-execution-plan-spec/regression_http_payload.util";
import { executeTransportWithRegistry } from "@tools-regression-execution-plan-spec/regression_transport_executor.util";

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function asString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function tryParseJson(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    return undefined;
  }
}

function resolveWatcherPollIntervalMs(args: {
  timeoutMs: number | undefined;
  retryMax: number | undefined;
}): number | undefined {
  if (typeof args.timeoutMs !== "number" || typeof args.retryMax !== "number") {
    return undefined;
  }
  if (args.timeoutMs <= 0 || args.retryMax <= 0) {
    return undefined;
  }
  return Math.max(25, Math.floor(args.timeoutMs / args.retryMax));
}

function watcherAssertionsAreRetryableMissingPath(assertions: Array<Record<string, unknown>> | undefined): boolean {
  if (!Array.isArray(assertions) || assertions.length === 0) {
    return false;
  }
  const blockedAssertions = assertions.filter((entry) => entry.status === "blocked_invalid");
  if (blockedAssertions.length === 0) {
    return false;
  }
  return blockedAssertions.every((entry) => entry.reasonCode === "actual_path_missing");
}

function watcherMissingPathRetryCap(retryMax: number): number {
  return Math.min(retryMax, 2);
}

function buildWatcherEnvelope(transport: {
  status: string;
  statusCode?: number;
  durationMs: number;
  bodyText?: string;
  bodyPreview?: string;
  headers?: Record<string, string>;
  reasonCode?: string;
}): Record<string, unknown> {
  const responseBody = transport.bodyText ?? transport.bodyPreview ?? "";
  return {
    status: transport.status === "pass" ? "pass" : "fail",
    response: {
      statusCode: transport.statusCode ?? 0,
      body: responseBody,
      ...(transport.headers ? { headers: transport.headers } : {}),
      ...(typeof responseBody === "string" ? { bodyJson: tryParseJson(responseBody) } : {}),
    },
    transport: {
      durationMs: transport.durationMs,
      reasonCode: transport.reasonCode ?? null,
    },
  };
}

function resolveWatcherPayload(args: {
  watcher: PlanWatcher;
  context: Record<string, unknown>;
  timeoutMs?: number;
}):
  | { ok: true; protocol: TransportProtocol; payload: Record<string, unknown> }
  | { ok: false; reasonCode: string; reasonMeta?: Record<string, unknown> } {
  const providerType = asString(args.watcher.provider?.type);
  if (!providerType) {
    return { ok: false, reasonCode: "watcher_runtime_configuration_invalid" };
  }

  const protocol = providerType === "http" || providerType === "grpc" || providerType === "kafka" || providerType === "custom"
    ? providerType
    : undefined;
  if (!protocol) {
    return {
      ok: false,
      reasonCode: "watcher_provider_not_supported",
      reasonMeta: { providerType },
    };
  }

  const transport = asRecord(args.watcher.provider?.transport);
  if (!transport) {
    return { ok: false, reasonCode: "watcher_runtime_configuration_invalid" };
  }

  const resolvedTransport = deepResolvePlaceholderValue(transport, args.context);
  const normalizedTransport = asRecord(resolvedTransport);
  if (!normalizedTransport) {
    return { ok: false, reasonCode: "watcher_runtime_configuration_invalid" };
  }

  if (protocol !== "http") {
    return {
      ok: false,
      reasonCode: "watcher_provider_not_supported",
      reasonMeta: { providerType },
    };
  }

  const candidatePayload =
    asRecord(normalizedTransport.http) ??
    asRecord(normalizedTransport.request) ??
    normalizedTransport;
  const payload = buildHttpPayload({
    resolvedTransport: { http: candidatePayload },
    context: args.context,
  });
  const inheritedTimeoutMs =
    typeof payload.timeoutMs === "number" && Number.isFinite(payload.timeoutMs) && payload.timeoutMs > 0
      ? Math.floor(payload.timeoutMs)
      : typeof args.context["runtime.requestTimeoutMs"] === "number" &&
          Number.isFinite(args.context["runtime.requestTimeoutMs"]) &&
          args.context["runtime.requestTimeoutMs"] > 0
        ? Math.floor(args.context["runtime.requestTimeoutMs"] as number)
        : undefined;
  const boundedTimeoutMs =
    typeof args.timeoutMs === "number" && args.timeoutMs > 0
      ? typeof inheritedTimeoutMs === "number"
        ? Math.min(inheritedTimeoutMs, args.timeoutMs)
        : args.timeoutMs
      : inheritedTimeoutMs;
  if (typeof boundedTimeoutMs === "number" && boundedTimeoutMs > 0) {
    payload.timeoutMs = boundedTimeoutMs;
  }

  return {
    ok: true,
    protocol,
    payload,
  };
}

export function deriveWatcherPhaseStatus(watchers: RegressionRunWatcherResult[] | undefined): RegressionWatcherPhaseStatus {
  if (!Array.isArray(watchers) || watchers.length === 0) {
    return "not_configured";
  }
  if (watchers.some((watcher) => watcher.status === "blocked_runtime" || watcher.status === "blocked_dependency")) {
    return "blocked";
  }
  if (watchers.some((watcher) => watcher.status === "fail_assertion")) {
    return "fail";
  }
  return "pass";
}

export function combinePlanRunStatus(args: {
  triggerStatus: RegressionRunStatus;
  watcherStatus: RegressionWatcherPhaseStatus;
}): RegressionRunStatus {
  if (args.triggerStatus === "blocked") return "blocked";
  if (args.triggerStatus === "fail") return "fail";
  if (args.watcherStatus === "blocked") return "blocked";
  if (args.watcherStatus === "fail") return "fail";
  return "pass";
}

export async function executeWatchers(args: {
  contract: PlanContract;
  resolvedContext: Record<string, unknown>;
  registry: Map<TransportProtocol, TransportAdapter>;
  stepRows: RegressionRunStepResult[];
  stepContextsByOrder: Map<number, Record<string, unknown>>;
}): Promise<{
  watcherRows: RegressionRunWatcherResult[];
  watcherEvidence: Array<Record<string, unknown>>;
}> {
  const watcherRows: RegressionRunWatcherResult[] = [];
  const watcherEvidence: Array<Record<string, unknown>> = [];
  const stepStatusByOrder = new Map<number, RegressionRunStepResult>(
    args.stepRows.map((row) => [row.order, row]),
  );
  const watchers = [...(args.contract.watchers ?? [])].sort((lhs, rhs) => {
    if (lhs.dependency.stepOrder !== rhs.dependency.stepOrder) {
      return lhs.dependency.stepOrder - rhs.dependency.stepOrder;
    }
    return lhs.id.localeCompare(rhs.id);
  });

  for (const watcher of watchers) {
    const startedAt = Date.now();
    const dependencyRow = stepStatusByOrder.get(watcher.dependency.stepOrder);
    const watcherContext = args.stepContextsByOrder.get(watcher.dependency.stepOrder) ?? args.resolvedContext;
    const resolvedWaitPolicy = resolveWatcherWaitPolicy({
      watcher,
      providedContext: watcherContext,
    });
    const pollIntervalMs = resolveWatcherPollIntervalMs({
      timeoutMs: resolvedWaitPolicy.timeoutMs,
      retryMax: resolvedWaitPolicy.retryMax,
    });
    const waitPolicy = {
      ...resolvedWaitPolicy,
      ...(typeof pollIntervalMs === "number" ? { pollIntervalMs } : {}),
    };
    const attempts: RegressionRunWatcherAttempt[] = [];

    const persistWatcher = (watcherRow: RegressionRunWatcherResult): void => {
      watcherRows.push(watcherRow);
      watcherEvidence.push({
        id: watcherRow.id,
        dependencyStepOrder: watcherRow.dependencyStepOrder,
        providerType: watcherRow.providerType,
        status: watcherRow.status,
        outcome: watcherRow.outcome,
        attemptCount: watcherRow.attemptCount,
        durationMs: watcherRow.durationMs,
        waitPolicy: watcherRow.waitPolicy,
        ...(Array.isArray(watcherRow.attempts) ? { attempts: watcherRow.attempts } : {}),
        ...(Array.isArray(watcherRow.assertions) ? { assertions: watcherRow.assertions } : {}),
        ...(typeof watcherRow.reasonCode === "string" ? { reasonCode: watcherRow.reasonCode } : {}),
        ...(asRecord(watcherRow.reasonMeta) ? { reasonMeta: watcherRow.reasonMeta } : {}),
      });
    };

    if (!dependencyRow || dependencyRow.status !== "pass") {
      persistWatcher({
        id: watcher.id,
        dependencyStepOrder: watcher.dependency.stepOrder,
        providerType: watcher.provider.type,
        status: "blocked_dependency",
        outcome: "blocked",
        attemptCount: 0,
        durationMs: Math.max(1, Date.now() - startedAt),
        waitPolicy,
        reasonCode: "watcher_dependency_not_satisfied",
        reasonMeta: {
          dependencyStepOrder: watcher.dependency.stepOrder,
          dependencyStatus: dependencyRow?.status ?? "missing",
        },
      });
      continue;
    }

    if (
      typeof resolvedWaitPolicy.timeoutMs !== "number" ||
      typeof resolvedWaitPolicy.retryMax !== "number" ||
      typeof pollIntervalMs !== "number"
    ) {
      persistWatcher({
        id: watcher.id,
        dependencyStepOrder: watcher.dependency.stepOrder,
        providerType: watcher.provider.type,
        status: "blocked_runtime",
        outcome: "blocked",
        attemptCount: 0,
        durationMs: Math.max(1, Date.now() - startedAt),
        waitPolicy,
        reasonCode: "watcher_wait_policy_unresolved",
      });
      continue;
    }

    const payload = resolveWatcherPayload({
      watcher,
      context: watcherContext,
      timeoutMs: resolvedWaitPolicy.timeoutMs,
    });
    if (!payload.ok) {
      persistWatcher({
        id: watcher.id,
        dependencyStepOrder: watcher.dependency.stepOrder,
        providerType: watcher.provider.type,
        status: "blocked_runtime",
        outcome: "blocked",
        attemptCount: 0,
        durationMs: Math.max(1, Date.now() - startedAt),
        waitPolicy,
        reasonCode: payload.reasonCode,
        ...(payload.reasonMeta ? { reasonMeta: payload.reasonMeta } : {}),
      });
      continue;
    }

    let finalAssertions: RegressionRunWatcherResult["assertions"] | undefined;
    let finalReasonCode: string | undefined;
    let finalReasonMeta: Record<string, unknown> | undefined;
    let finalStatus: RegressionRunWatcherResult["status"] = "blocked_runtime";
    let finalOutcome: RegressionRunWatcherOutcome = "blocked";

    for (let attempt = 1; attempt <= resolvedWaitPolicy.retryMax; attempt += 1) {
      const attemptStartedAt = Date.now();
      const elapsedBeforeAttempt = attemptStartedAt - startedAt;
      if (elapsedBeforeAttempt >= resolvedWaitPolicy.timeoutMs) {
        finalStatus = "blocked_runtime";
        finalOutcome = "timed_out";
        finalReasonCode = "watcher_timeout_exceeded";
        finalReasonMeta = {
          timeoutMs: resolvedWaitPolicy.timeoutMs,
          retryMax: resolvedWaitPolicy.retryMax,
        };
        break;
      }

      const transport = await executeTransportWithRegistry({
        protocol: payload.protocol,
        payload: payload.payload,
        registry: args.registry,
      });
      const observedAt = new Date().toISOString();
      attempts.push(buildWatcherAttemptRecord({ attempt, transport, observedAt }));

      if (transport.status === "blocked_invalid") {
        finalStatus = "blocked_runtime";
        finalOutcome = "blocked";
        finalReasonCode = "watcher_runtime_configuration_invalid";
        finalReasonMeta = buildTransportReasonMeta(transport);
        break;
      }

      if (transport.status === "blocked_runtime") {
        finalStatus = "blocked_runtime";
        finalOutcome = "blocked";
        finalReasonCode = "watcher_target_unreachable";
        finalReasonMeta = buildTransportReasonMeta(transport);
        break;
      }

      const watcherEnvelope = buildWatcherEnvelope(transport);
      const evaluated = evaluateStepExpectations({
        stepResult: watcherEnvelope,
        expectations: watcher.expect,
        transportFailure: transport.status === "fail_http",
        dependencyBlocked: false,
      });
      finalAssertions = evaluated.assertions;

      if (evaluated.status === "pass") {
        finalStatus = "pass";
        finalOutcome = "verified";
        finalReasonCode = "ok";
        finalReasonMeta = undefined;
        break;
      }

      if (evaluated.status === "blocked_runtime") {
        if (watcherAssertionsAreRetryableMissingPath(evaluated.assertions as Array<Record<string, unknown>>)) {
          if (attempt < watcherMissingPathRetryCap(resolvedWaitPolicy.retryMax)) {
            const elapsedAfterAttempt = Date.now() - startedAt;
            const remainingMs = resolvedWaitPolicy.timeoutMs - elapsedAfterAttempt;
            if (remainingMs <= 0) {
              finalStatus = "blocked_runtime";
              finalOutcome = "timed_out";
              finalReasonCode = "watcher_timeout_exceeded";
              finalReasonMeta = {
                timeoutMs: resolvedWaitPolicy.timeoutMs,
                retryMax: resolvedWaitPolicy.retryMax,
              };
              break;
            }
            await sleep(Math.min(pollIntervalMs, remainingMs));
            continue;
          }
          finalStatus = "blocked_runtime";
          finalOutcome = "blocked";
          finalReasonCode = "watcher_runtime_configuration_invalid";
          finalReasonMeta = {
            cause: "actual_path_missing_persistent",
            retryCap: watcherMissingPathRetryCap(resolvedWaitPolicy.retryMax),
          };
          break;
        }
        finalStatus = "blocked_runtime";
        finalOutcome = "blocked";
        finalReasonCode = "watcher_runtime_configuration_invalid";
        finalReasonMeta = buildTransportReasonMeta(transport);
        break;
      }

      finalStatus = "fail_assertion";
      finalOutcome = "failed_expectation";
      finalReasonCode = "watcher_expectation_not_satisfied";

      if (attempt < resolvedWaitPolicy.retryMax) {
        const elapsedAfterAttempt = Date.now() - startedAt;
        const remainingMs = resolvedWaitPolicy.timeoutMs - elapsedAfterAttempt;
        if (remainingMs <= 0) {
          finalStatus = "blocked_runtime";
          finalOutcome = "timed_out";
          finalReasonCode = "watcher_timeout_exceeded";
          finalReasonMeta = {
            timeoutMs: resolvedWaitPolicy.timeoutMs,
            retryMax: resolvedWaitPolicy.retryMax,
          };
          break;
        }
        await sleep(Math.min(pollIntervalMs, remainingMs));
      }
    }

    persistWatcher({
      id: watcher.id,
      dependencyStepOrder: watcher.dependency.stepOrder,
      providerType: watcher.provider.type,
      status: finalStatus,
      outcome: finalOutcome,
      attemptCount: attempts.length,
      durationMs: Math.max(1, Date.now() - startedAt),
      waitPolicy,
      ...(typeof finalReasonCode === "string" ? { reasonCode: finalReasonCode } : {}),
      ...(finalReasonMeta ? { reasonMeta: finalReasonMeta } : {}),
      ...(finalAssertions ? { assertions: finalAssertions } : {}),
      attempts,
    });
  }

  return { watcherRows, watcherEvidence };
}

function buildTransportReasonMeta(transport: TransportExecutionResult): Record<string, unknown> | undefined {
  return {
    ...(transport.reasonMeta ?? {}),
    ...(transport.reasonCode ? { transportReasonCode: transport.reasonCode } : {}),
  };
}

function buildWatcherAttemptRecord(args: {
  attempt: number;
  transport: TransportExecutionResult;
  observedAt: string;
}): RegressionRunWatcherAttempt {
  return {
    attempt: args.attempt,
    status: args.transport.status,
    durationMs: args.transport.durationMs,
    ...(typeof args.transport.statusCode === "number" ? { statusCode: args.transport.statusCode } : {}),
    ...(typeof args.transport.reasonCode === "string" ? { reasonCode: args.transport.reasonCode } : {}),
    observedAt: args.observedAt,
  };
}
