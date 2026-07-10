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
import { resolveWatcherWaitPolicy } from "../../../spec/regression-execution-plan-spec/src/regression_watcher_contract.util";
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

function watcherMissingPathRetryCap(retryMax: number): number {
  return Math.min(retryMax, 2);
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
  orchestrationDeadlineEpochMs?: number;
  nowMs?: () => number;
  sleepMs?: (ms: number) => Promise<void>;
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

  const buildContinuation = (watcherIndex: number, phaseStartedAt: string): RegressionExecutionContinuation => ({
    phase: "watchers",
    watcherIndex,
    phaseStartedAt,
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
    if (shouldYield()) {
      return {
        watcherRows,
        watcherEvidence,
        phaseStatus: "in_progress",
        continuation: buildContinuation(watcherIndex, phaseStartedAt),
      };
    }

    const startedAtMs = Date.parse(phaseStartedAt);
    const startedAt = Number.isFinite(startedAtMs) ? startedAtMs : nowMs();
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
      watcherEvidence.push(toWatcherExecutionEvidence(watcherRow));
    };

    if (!dependencyRow || dependencyRow.status !== "pass") {
      persistWatcher({
        id: watcher.id,
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

    let finalAssertions: RegressionRunWatcherResult["assertions"] | undefined;
    let finalObservation: Record<string, unknown> | undefined;
    let finalReasonCode: RegressionWatcherReasonCode | undefined;
    let finalReasonMeta: Record<string, unknown> | undefined;
    let finalStatus: RegressionRunWatcherResult["status"] = "blocked_runtime";
    let finalOutcome: RegressionRunWatcherOutcome = "blocked";

    for (let attempt = 1; attempt <= resolvedWaitPolicy.retryMax; attempt += 1) {
      if (shouldYield()) {
        return {
          watcherRows,
          watcherEvidence,
          phaseStatus: "in_progress",
          continuation: buildContinuation(watcherIndex, phaseStartedAt),
        };
      }
      const attemptStartedAt = nowMs();
      const elapsedBeforeAttempt = attemptStartedAt - startedAt;
      if (elapsedBeforeAttempt >= resolvedWaitPolicy.timeoutMs) {
        finalStatus = "blocked_runtime";
        finalOutcome = "timed_out";
        finalReasonCode = "watcher_timeout";
        finalReasonMeta = {
          timeoutMs: resolvedWaitPolicy.timeoutMs,
          retryMax: resolvedWaitPolicy.retryMax,
        };
        break;
      }

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
          if (attempt < watcherMissingPathRetryCap(resolvedWaitPolicy.retryMax)) {
            const elapsedAfterAttempt = nowMs() - startedAt;
            const remainingMs = resolvedWaitPolicy.timeoutMs - elapsedAfterAttempt;
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
                continuation: buildContinuation(watcherIndex, phaseStartedAt),
              };
            }
            await sleepMs(
              typeof deadlineSleepMs === "number" ? Math.min(sleepDurationMs, deadlineSleepMs) : sleepDurationMs,
            );
            continue;
          }
          finalStatus = "blocked_runtime";
          finalOutcome = "blocked";
          finalReasonCode = "watcher_configuration_invalid";
          finalReasonMeta = {
            cause: "actual_path_missing_persistent",
            retryCap: watcherMissingPathRetryCap(resolvedWaitPolicy.retryMax),
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
        const elapsedAfterAttempt = nowMs() - startedAt;
        const remainingMs = resolvedWaitPolicy.timeoutMs - elapsedAfterAttempt;
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
            continuation: buildContinuation(watcherIndex, phaseStartedAt),
          };
        }
        await sleepMs(
          typeof deadlineSleepMs === "number" ? Math.min(sleepDurationMs, deadlineSleepMs) : sleepDurationMs,
        );
      }
    }

    persistWatcher({
      id: watcher.id,
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
