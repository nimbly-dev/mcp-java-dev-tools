import type {
  PlanContract,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_execution_plan_spec.model";
import type {
  RegressionExecutionContinuation,
  RegressionRunStatus,
  RegressionRunWatcherAttempt,
  RegressionWatcherReasonCode,
  RegressionRunWatcherOutcome,
  WatcherExecutionEvidence,
  RegressionRunWatcherResult,
  RegressionWatcherPhaseStatus,
  RegressionRunStepResult,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_run_artifact.model";
import type { TransportAdapter, TransportProtocol, TransportExecutionResult } from "../../../spec/regression-execution-plan-spec/src/models/regression_transport.model";
import { resolveWatcherWaitPolicy } from "./regression_watcher_policy";
import {
  evaluateStepExpectations,
} from "../shared/regression_expectation_evaluator";
import { executeTransportWithRegistry } from "../shared/regression_transport_executor";
import {
  normalizeWatcherProviderResult,
  resolveWatcherProviderExecution,
  summarizeWatcherObservation,
} from "./regression_watcher_provider";

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
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
  if (args.triggerStatus === "in_progress" || args.watcherStatus === "in_progress") return "in_progress";
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
  priorWatcherRows?: RegressionRunWatcherResult[];
  priorWatcherEvidence?: WatcherExecutionEvidence[];
  startWatcherIndex?: number;
  currentWatcherStartedAt?: string;
  continuation?: RegressionExecutionContinuation;
  orchestrationDeadlineEpochMs?: number;
  nowMs?: () => number;
  sleepMs?: (ms: number) => Promise<void>;
  renewSuiteLease?: (deadlineAtEpochMs?: number) => Promise<void>;
}): Promise<{
  watcherRows: RegressionRunWatcherResult[];
  watcherEvidence: WatcherExecutionEvidence[];
  phaseStatus: RegressionWatcherPhaseStatus;
  continuation?: RegressionExecutionContinuation;
}> {
  const nowMs = args.nowMs ?? (() => Date.now());
  const sleepMs = args.sleepMs ?? sleep;
  const watcherRows: RegressionRunWatcherResult[] = [...(args.priorWatcherRows ?? [])];
  const watcherEvidence: WatcherExecutionEvidence[] = [...(args.priorWatcherEvidence ?? [])];
  const stepStatusByOrder = new Map<number, RegressionRunStepResult>(
    args.stepRows.map((row) => [row.order, row]),
  );
  const watchers = [...(args.contract.watchers ?? [])].sort((lhs, rhs) => {
    if (lhs.dependency.stepOrder !== rhs.dependency.stepOrder) {
      return lhs.dependency.stepOrder - rhs.dependency.stepOrder;
    }
    return lhs.id.localeCompare(rhs.id);
  });
  const startWatcherIndex =
    typeof args.startWatcherIndex === "number" && Number.isInteger(args.startWatcherIndex) && args.startWatcherIndex >= 0
      ? args.startWatcherIndex
      : 0;

  const shouldYield = (): boolean =>
    typeof args.orchestrationDeadlineEpochMs === "number" && nowMs() >= args.orchestrationDeadlineEpochMs;

  const sleepWithLeaseRenewal = async (durationMs: number, deadlineAtEpochMs?: number): Promise<void> => {
    let remainingMs = Math.max(0, durationMs);
    while (remainingMs > 0) {
      const sliceMs = Math.min(remainingMs, 10_000);
      await sleepMs(sliceMs);
      remainingMs -= sliceMs;
      if (remainingMs > 0 && args.renewSuiteLease) await args.renewSuiteLease(deadlineAtEpochMs);
    }
  };

  const buildContinuation = (watcherIndex: number, phaseStartedAt: string, state?: {
    watcherName?: string;
    dependencyStepOrder?: number;
    providerType?: string;
    deadlineAtEpochMs?: number;
    timeoutMs?: number;
    pollIntervalMs?: number;
    retryMax?: number;
    attempts?: RegressionRunWatcherAttempt[];
    nextAttemptAt?: string;
    lastObservation?: Record<string, unknown>;
    lastAssertion?: RegressionRunWatcherResult["assertions"];
  }): RegressionExecutionContinuation => ({
    phase: "watchers",
    watcherIndex,
    phaseStartedAt,
    ...(state?.watcherName ? { watcherName: state.watcherName } : {}),
    ...(typeof state?.dependencyStepOrder === "number" ? { dependencyStepOrder: state.dependencyStepOrder } : {}),
    ...(state?.providerType ? { providerType: state.providerType } : {}),
    ...(typeof state?.deadlineAtEpochMs === "number" ? { deadlineAtEpochMs: state.deadlineAtEpochMs } : {}),
    ...(typeof state?.timeoutMs === "number" ? { timeoutMs: state.timeoutMs } : {}),
    ...(typeof state?.pollIntervalMs === "number" ? { pollIntervalMs: state.pollIntervalMs } : {}),
    ...(typeof state?.retryMax === "number" ? { retryMax: state.retryMax } : {}),
    ...(state?.attempts && state.attempts.length > 0 ? { attemptCount: state.attempts.length, attempts: state.attempts.slice(-25) } : {}),
    ...(state?.nextAttemptAt ? { nextAttemptAt: state.nextAttemptAt } : {}),
    ...(state?.lastObservation ? { lastObservation: state.lastObservation } : {}),
    ...(state?.lastAssertion ? { lastAssertion: { assertions: state.lastAssertion } } : {}),
  });

  for (let watcherIndex = startWatcherIndex; watcherIndex < watchers.length; watcherIndex += 1) {
    const watcher = watchers[watcherIndex];
    if (!watcher) {
      continue;
    }
    const phaseStartedAt =
      watcherIndex === startWatcherIndex && typeof args.currentWatcherStartedAt === "string"
        ? args.currentWatcherStartedAt
        : new Date(nowMs()).toISOString();
    const startedAtMs = Date.parse(phaseStartedAt);
    const startedAt = Number.isFinite(startedAtMs) ? startedAtMs : nowMs();
    const dependencyRow = stepStatusByOrder.get(watcher.dependency.stepOrder);
    const watcherContext = args.stepContextsByOrder.get(watcher.dependency.stepOrder) ?? args.resolvedContext;
    const persistedContinuation =
      watcherIndex === startWatcherIndex && args.continuation?.phase === "watchers" && args.continuation.watcherIndex === watcherIndex
        ? args.continuation
        : undefined;
    const resolvedWaitPolicy = persistedContinuation && typeof persistedContinuation.timeoutMs === "number" && typeof persistedContinuation.retryMax === "number"
      ? {
          timeoutMs: persistedContinuation.timeoutMs,
          retryMax: persistedContinuation.retryMax,
          timeoutSource: "watcher_override" as const,
          retrySource: "watcher_override" as const,
        }
      : resolveWatcherWaitPolicy({
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
    const watcherDeadlineAtEpochMs = persistedContinuation?.deadlineAtEpochMs
      ?? (typeof resolvedWaitPolicy.timeoutMs === "number" ? startedAtMs + resolvedWaitPolicy.timeoutMs : startedAtMs);
    if (shouldYield()) {
      return {
        watcherRows,
        watcherEvidence,
        phaseStatus: "in_progress",
        continuation: buildContinuation(watcherIndex, phaseStartedAt, {
          watcherName: watcher.id,
          dependencyStepOrder: watcher.dependency.stepOrder,
          providerType: watcher.provider.type,
          deadlineAtEpochMs: watcherDeadlineAtEpochMs,
          ...(typeof resolvedWaitPolicy.timeoutMs === "number" ? { timeoutMs: resolvedWaitPolicy.timeoutMs } : {}),
          ...(typeof pollIntervalMs === "number" ? { pollIntervalMs } : {}),
          ...(typeof resolvedWaitPolicy.retryMax === "number" ? { retryMax: resolvedWaitPolicy.retryMax } : {}),
        }),
      };
    }
    const attempts: RegressionRunWatcherAttempt[] =
      watcherIndex === startWatcherIndex && args.continuation?.phase === "watchers" && args.continuation.watcherIndex === watcherIndex
        ? [...(args.continuation?.attempts ?? [])]
        : [];

    const persistWatcher = (watcherRow: RegressionRunWatcherResult): void => {
      watcherRows.push(watcherRow);
      watcherEvidence.push(toWatcherExecutionEvidence(watcherRow));
    };

    if (!dependencyRow || dependencyRow.status !== "pass") {
      persistWatcher({
        id: watcher.id,
        startedAtEpochMs: startedAt,
        deadlineAtEpochMs: watcherDeadlineAtEpochMs,
        dependencyStepOrder: watcher.dependency.stepOrder,
        providerType: watcher.provider.type,
        status: "blocked_dependency",
        outcome: "blocked",
        attemptCount: 0,
        durationMs: Math.max(1, nowMs() - startedAt),
        waitPolicy,
        reasonCode: "watcher_dependency_invalid",
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
        startedAtEpochMs: startedAt,
        deadlineAtEpochMs: watcherDeadlineAtEpochMs,
        dependencyStepOrder: watcher.dependency.stepOrder,
        providerType: watcher.provider.type,
        status: "blocked_runtime",
        outcome: "blocked",
        attemptCount: 0,
        durationMs: Math.max(1, nowMs() - startedAt),
        waitPolicy,
        reasonCode: "watcher_configuration_invalid",
        reasonMeta: {
          cause: "wait_policy_unresolved",
        },
      });
      continue;
    }

    const providerExecution = resolveWatcherProviderExecution({
      watcher,
      context: watcherContext,
      timeoutMs: resolvedWaitPolicy.timeoutMs,
    });
    if (!providerExecution.ok) {
      const providerReasonMeta = asRecord(providerExecution.reasonMeta);
      persistWatcher({
        id: watcher.id,
        startedAtEpochMs: startedAt,
        deadlineAtEpochMs: watcherDeadlineAtEpochMs,
        dependencyStepOrder: watcher.dependency.stepOrder,
        providerType: watcher.provider.type,
        status: "blocked_runtime",
        outcome: "blocked",
        attemptCount: 0,
        durationMs: Math.max(1, nowMs() - startedAt),
        waitPolicy,
        reasonCode: "watcher_configuration_invalid",
        reasonMeta: {
          ...(providerReasonMeta ?? {}),
          providerReasonCode: providerExecution.reasonCode,
        },
      });
      continue;
    }

    let finalAssertions: RegressionRunWatcherResult["assertions"] | undefined =
      args.continuation?.phase === "watchers" && args.continuation.watcherIndex === watcherIndex
        ? args.continuation.lastAssertion?.assertions as RegressionRunWatcherResult["assertions"] | undefined
        : undefined;
    let finalObservation: Record<string, unknown> | undefined =
      args.continuation?.phase === "watchers" && args.continuation.watcherIndex === watcherIndex
        ? args.continuation.lastObservation
        : undefined;
    let finalReasonCode: RegressionWatcherReasonCode | undefined;
    let finalReasonMeta: Record<string, unknown> | undefined;
    let finalStatus: RegressionRunWatcherResult["status"] = "blocked_runtime";
    let finalOutcome: RegressionRunWatcherOutcome = "blocked";

    for (let attempt = attempts.length + 1; attempt <= resolvedWaitPolicy.retryMax; attempt += 1) {
      if (shouldYield()) {
        return {
          watcherRows,
          watcherEvidence,
          phaseStatus: "in_progress",
          continuation: buildContinuation(watcherIndex, phaseStartedAt, { watcherName: watcher.id, dependencyStepOrder: watcher.dependency.stepOrder, providerType: watcher.provider.type, deadlineAtEpochMs: watcherDeadlineAtEpochMs, ...(typeof resolvedWaitPolicy.timeoutMs === "number" ? { timeoutMs: resolvedWaitPolicy.timeoutMs } : {}), pollIntervalMs, ...(typeof resolvedWaitPolicy.retryMax === "number" ? { retryMax: resolvedWaitPolicy.retryMax } : {}), attempts, ...(finalObservation ? { lastObservation: finalObservation } : {}), ...(finalAssertions ? { lastAssertion: finalAssertions } : {}) }),
        };
      }
      const attemptStartedAt = nowMs();
      if (attemptStartedAt >= watcherDeadlineAtEpochMs) {
        finalStatus = "blocked_runtime";
        finalOutcome = "timed_out";
        finalReasonCode = "watcher_timeout";
        finalReasonMeta = {
          timeoutMs: resolvedWaitPolicy.timeoutMs,
          retryMax: resolvedWaitPolicy.retryMax,
        };
        break;
      }
      if (args.renewSuiteLease) await args.renewSuiteLease(watcherDeadlineAtEpochMs);

      const transport = await executeTransportWithRegistry({
        protocol: providerExecution.execution.protocol,
        payload: providerExecution.execution.payload,
        registry: args.registry,
      });
      const observedAt = new Date(nowMs()).toISOString();
      attempts.push(buildWatcherAttemptRecord({ attempt, transport, observedAt }));

      if (transport.status === "blocked_invalid") {
        finalStatus = "blocked_runtime";
        finalOutcome = "blocked";
        finalReasonCode = "watcher_configuration_invalid";
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

      const normalized = normalizeWatcherProviderResult({
        execution: providerExecution.execution,
        transport,
      });
      if (!normalized.ok) {
        finalStatus = "blocked_runtime";
        finalOutcome = "blocked";
        finalReasonCode = "watcher_configuration_invalid";
        finalReasonMeta = {
          ...normalized.reasonMeta,
          providerReasonCode: normalized.reasonCode,
        };
        break;
      }

      const watcherEnvelope = normalized.envelope;
      finalObservation = summarizeWatcherObservation(watcherEnvelope);
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
        finalReasonCode = "watcher_verified";
        finalReasonMeta = undefined;
        break;
      }

      if (evaluated.status === "blocked_runtime") {
        if (watcherAssertionsAreRetryableMissingPath(evaluated.assertions as Array<Record<string, unknown>>)) {
          const remainingMs = watcherDeadlineAtEpochMs - nowMs();
          if (remainingMs <= 0) {
            finalStatus = "blocked_runtime";
            finalOutcome = "timed_out";
            finalReasonCode = "watcher_timeout";
            finalReasonMeta = {
              timeoutMs: resolvedWaitPolicy.timeoutMs,
              retryMax: resolvedWaitPolicy.retryMax,
            };
            break;
          }
          if (attempt < resolvedWaitPolicy.retryMax) {
            const sleepDurationMs = Math.min(pollIntervalMs, remainingMs);
            const deadlineSleepMs =
              typeof args.orchestrationDeadlineEpochMs === "number"
                ? args.orchestrationDeadlineEpochMs - nowMs()
                : undefined;
            if (typeof deadlineSleepMs === "number" && deadlineSleepMs <= 0) {
              return {
                watcherRows,
                watcherEvidence,
                phaseStatus: "in_progress",
                continuation: buildContinuation(watcherIndex, phaseStartedAt, { watcherName: watcher.id, dependencyStepOrder: watcher.dependency.stepOrder, providerType: watcher.provider.type, deadlineAtEpochMs: watcherDeadlineAtEpochMs, ...(typeof resolvedWaitPolicy.timeoutMs === "number" ? { timeoutMs: resolvedWaitPolicy.timeoutMs } : {}), pollIntervalMs, ...(typeof resolvedWaitPolicy.retryMax === "number" ? { retryMax: resolvedWaitPolicy.retryMax } : {}), attempts, nextAttemptAt: new Date(nowMs() + sleepDurationMs).toISOString(), ...(finalObservation ? { lastObservation: finalObservation } : {}), ...(finalAssertions ? { lastAssertion: finalAssertions } : {}) }),
              };
            }
            await sleepWithLeaseRenewal(
              typeof deadlineSleepMs === "number" ? Math.min(sleepDurationMs, deadlineSleepMs) : sleepDurationMs,
              watcherDeadlineAtEpochMs,
            );
            continue;
          }
          finalStatus = "blocked_runtime";
          finalOutcome = "blocked";
          finalReasonCode = "watcher_actual_path_missing_retry_exhausted";
          finalReasonMeta = {
            retryMax: resolvedWaitPolicy.retryMax,
          };
          break;
        }
        finalStatus = "blocked_runtime";
        finalOutcome = "blocked";
        finalReasonCode = "watcher_configuration_invalid";
        finalReasonMeta = buildTransportReasonMeta(transport);
        break;
      }

      finalStatus = "fail_assertion";
      finalOutcome = "failed_expectation";
      finalReasonCode = "watcher_expectation_failed";

      if (attempt < resolvedWaitPolicy.retryMax) {
        const remainingMs = watcherDeadlineAtEpochMs - nowMs();
        if (remainingMs <= 0) {
          finalStatus = "blocked_runtime";
          finalOutcome = "timed_out";
          finalReasonCode = "watcher_timeout";
          finalReasonMeta = {
            timeoutMs: resolvedWaitPolicy.timeoutMs,
            retryMax: resolvedWaitPolicy.retryMax,
          };
          break;
        }
        const sleepDurationMs = Math.min(pollIntervalMs, remainingMs);
        const deadlineSleepMs =
          typeof args.orchestrationDeadlineEpochMs === "number"
            ? args.orchestrationDeadlineEpochMs - nowMs()
            : undefined;
        if (typeof deadlineSleepMs === "number" && deadlineSleepMs <= 0) {
          return {
            watcherRows,
            watcherEvidence,
            phaseStatus: "in_progress",
            continuation: buildContinuation(watcherIndex, phaseStartedAt, { watcherName: watcher.id, dependencyStepOrder: watcher.dependency.stepOrder, providerType: watcher.provider.type, deadlineAtEpochMs: watcherDeadlineAtEpochMs, ...(typeof resolvedWaitPolicy.timeoutMs === "number" ? { timeoutMs: resolvedWaitPolicy.timeoutMs } : {}), pollIntervalMs, ...(typeof resolvedWaitPolicy.retryMax === "number" ? { retryMax: resolvedWaitPolicy.retryMax } : {}), attempts, nextAttemptAt: new Date(nowMs() + sleepDurationMs).toISOString(), ...(finalObservation ? { lastObservation: finalObservation } : {}), ...(finalAssertions ? { lastAssertion: finalAssertions } : {}) }),
          };
        }
        await sleepWithLeaseRenewal(
          typeof deadlineSleepMs === "number" ? Math.min(sleepDurationMs, deadlineSleepMs) : sleepDurationMs,
          watcherDeadlineAtEpochMs,
        );
      }
    }

    persistWatcher({
      id: watcher.id,
      startedAtEpochMs: startedAt,
      deadlineAtEpochMs: watcherDeadlineAtEpochMs,
      dependencyStepOrder: watcher.dependency.stepOrder,
      providerType: watcher.provider.type,
      status: finalStatus,
      outcome: finalOutcome,
      attemptCount: attempts.length,
      durationMs: Math.max(1, nowMs() - startedAt),
      waitPolicy,
      ...(typeof finalReasonCode === "string" ? { reasonCode: finalReasonCode } : {}),
      ...(finalReasonMeta ? { reasonMeta: finalReasonMeta } : {}),
      ...(finalObservation ? { lastObservation: finalObservation } : {}),
      ...(finalAssertions ? { assertions: finalAssertions } : {}),
      attempts,
    });
  }

  return {
    watcherRows,
    watcherEvidence,
    phaseStatus: deriveWatcherPhaseStatus(watcherRows),
  };
}

function buildTransportReasonMeta(transport: TransportExecutionResult): Record<string, unknown> | undefined {
  const transportReasonMeta = asRecord(transport.reasonMeta);
  return {
    ...(transportReasonMeta ?? {}),
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

function toWatcherExecutionEvidence(watcherRow: RegressionRunWatcherResult): WatcherExecutionEvidence {
  const reasonMeta = asRecord(watcherRow.reasonMeta);
  return {
    id: watcherRow.id,
    dependencyStepOrder: watcherRow.dependencyStepOrder,
    providerType: watcherRow.providerType,
    status:
      watcherRow.outcome === "timed_out"
        ? "timed_out"
        : watcherRow.status === "pass"
          ? "ok"
          : "fail_closed",
    outcome:
      watcherRow.outcome === "verified"
        ? "verified"
        : watcherRow.outcome === "timed_out"
          ? "timeout"
          : watcherRow.reasonCode === "watcher_target_unreachable"
            ? "target_unreachable"
            : watcherRow.reasonCode === "watcher_expectation_failed"
              ? "expectation_failed"
              : watcherRow.reasonCode === "watcher_actual_path_missing_retry_exhausted"
                ? "expectation_failed"
              : watcherRow.reasonCode === "watcher_dependency_invalid"
                ? "dependency_invalid"
                : "configuration_invalid",
    attemptCount: watcherRow.attemptCount,
    durationMs: watcherRow.durationMs,
    reasonCode: watcherRow.reasonCode ?? "watcher_configuration_invalid",
    waitPolicy: watcherRow.waitPolicy,
    ...(asRecord(watcherRow.lastObservation) ? { lastObservation: watcherRow.lastObservation } : {}),
    ...(Array.isArray(watcherRow.attempts) ? { attempts: watcherRow.attempts } : {}),
    ...(Array.isArray(watcherRow.assertions) ? { assertions: watcherRow.assertions } : {}),
    ...(reasonMeta ? { reasonMeta } : {}),
  };
}
