import {
  openRunStateStore,
  persistRegressionSuiteState,
  readRegressionSuiteCheckpoint,
  upsertRunStateArtifact,
} from "@tools-feature-artifact-management";
import type { readRegressionSuiteState } from "@tools-feature-artifact-management";
import { buildSuiteStatusArtifactRelPath } from "@tools-regression-suite";
import type { RuntimeSuiteRunResult } from "../../../spec/regression-execution-plan-spec/src/models/regression_runtime_suite.model";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function runtimeSuiteFromSQLiteState(args: {
  state: ReturnType<typeof readRegressionSuiteState>;
}): RuntimeSuiteRunResult | null {
  if (!args.state) return null;
  const snapshot = args.state.checkpoint.continuation?.runtimeSuite;
  if (!isRecord(snapshot)) return null;
  if (
    typeof snapshot.executionProfile !== "string" ||
    (snapshot.executionPolicy !== "stop_on_fail" && snapshot.executionPolicy !== "continue_on_fail")
  ) {
    return null;
  }
  const result: RuntimeSuiteRunResult = {
    executionProfile: snapshot.executionProfile,
    executionPolicy: snapshot.executionPolicy,
    status: args.state.checkpoint.status,
    suiteRunId: args.state.checkpoint.suiteRunId,
    planRuns: args.state.planRuns.map((entry) => ({
      order: entry.planOrder ?? 0,
      planName: entry.planName,
      status: entry.status,
      runId: entry.runId,
      ...(entry.runStatus ? { runStatus: entry.runStatus } : {}),
      ...(entry.reasonCode ? { blockedReasonCode: entry.reasonCode } : {}),
    })),
    ...(typeof args.state.checkpoint.nextPlanOrder === "number"
      ? { nextPlanOrder: args.state.checkpoint.nextPlanOrder }
      : {}),
    ...(typeof snapshot.completedPlanCount === "number"
      ? { completedPlanCount: snapshot.completedPlanCount }
      : {}),
    ...(isRecord(snapshot.suiteContext) ? { suiteContext: snapshot.suiteContext } : {}),
    ...(isRecord(snapshot.progressSummary)
      ? {
          progressSummary: snapshot.progressSummary as NonNullable<
            RuntimeSuiteRunResult["progressSummary"]
          >,
        }
      : {}),
    ...(typeof snapshot.reasonCode === "string" ? { reasonCode: snapshot.reasonCode } : {}),
    ...(isRecord(snapshot.reasonMeta) ? { reasonMeta: snapshot.reasonMeta } : {}),
  };
  if (Array.isArray(snapshot.correlations)) {
    result.correlations = snapshot.correlations as NonNullable<
      RuntimeSuiteRunResult["correlations"]
    >;
  }
  return result;
}

export class CheckpointPersistenceError extends Error {
  constructor(readonly reasonCode: string) {
    super(reasonCode);
  }
}

export async function persistSQLiteSuiteCheckpoint(args: {
  workspaceRootAbs: string;
  projectName: string;
  suite: RuntimeSuiteRunResult;
  ownerId: string;
  linkLegacyArtifact: boolean;
}): Promise<void> {
  const suiteRunId = args.suite.suiteRunId?.trim();
  if (!suiteRunId) throw new CheckpointPersistenceError("suite_checkpoint_invalid");
  const store = await openRunStateStore({
    workspaceRootAbs: args.workspaceRootAbs,
    projectName: args.projectName,
  });
  if (!store.ok) throw new CheckpointPersistenceError(store.reasonCode);
  try {
    const priorCheckpoint = readRegressionSuiteCheckpoint({ store, suiteRunId });
    const activePlan = args.suite.progressSummary?.activePlan;
    const preserveActiveWatcherCheckpoint =
      args.suite.status === "blocked" &&
      (args.suite.reasonCode === "orchestrator_poll_limit_exhausted" ||
        args.suite.reasonCode === "orchestrator_timeout_budget_exhausted") &&
      activePlan?.phase === "watchers";
    const checkpointStatus = preserveActiveWatcherCheckpoint ? "in_progress" : args.suite.status;
    const now = Date.now();
    const persisted = persistRegressionSuiteState({
      store,
      checkpoint: {
        suiteRunId,
        executionProfile: args.suite.executionProfile,
        status: checkpointStatus,
        startedAtEpochMs: priorCheckpoint?.startedAtEpochMs ?? now,
        updatedAtEpochMs: now,
        ...(typeof args.suite.nextPlanOrder === "number"
          ? { nextPlanOrder: args.suite.nextPlanOrder }
          : preserveActiveWatcherCheckpoint && typeof activePlan?.order === "number"
            ? { nextPlanOrder: activePlan.order }
            : {}),
        ...(activePlan
          ? {
              activePlanName: activePlan.planName,
              activePlanOrder: activePlan.order,
              ...(typeof activePlan.runId === "string" ? { activeRunId: activePlan.runId } : {}),
              activePhase: activePlan.phase,
            }
          : {}),
        ...(checkpointStatus === "in_progress" ? {} : { completedAtEpochMs: now }),
        ...(typeof args.suite.reasonCode === "string" ? { reasonCode: args.suite.reasonCode } : {}),
        continuation: {
          runtimeSuite: {
            executionProfile: args.suite.executionProfile,
            executionPolicy: args.suite.executionPolicy,
            ...(typeof args.suite.completedPlanCount === "number"
              ? { completedPlanCount: args.suite.completedPlanCount }
              : {}),
            ...(typeof args.suite.reasonCode === "string"
              ? { reasonCode: args.suite.reasonCode }
              : {}),
            ...(args.suite.reasonMeta ? { reasonMeta: args.suite.reasonMeta } : {}),
            ...(args.suite.suiteContext ? { suiteContext: args.suite.suiteContext } : {}),
            ...(args.suite.progressSummary ? { progressSummary: args.suite.progressSummary } : {}),
            ...(args.suite.correlations ? { correlations: args.suite.correlations } : {}),
          },
          ...(args.suite.planRuns.length > 0 ? { planRuns: args.suite.planRuns } : {}),
        },
        ...(priorCheckpoint ? { expectedRevision: priorCheckpoint.revision } : {}),
        ownerId: args.ownerId,
        leaseExpiresAtEpochMs: now + 30_000,
      },
      planRuns: args.suite.planRuns
        .filter((entry) => typeof entry.runId === "string" && entry.runId.length > 0)
        .map((entry) => ({
          planName: entry.planName,
          runId: String(entry.runId),
          status: entry.status,
          runDirPathRel: `.mcpjvm/${args.projectName}/plans/regression/${entry.planName}/runs/${entry.runId}`,
          planOrder: entry.order,
          ...(entry.runStatus ? { runStatus: entry.runStatus } : {}),
          ...(entry.runStatus !== "in_progress" ? { completedAtEpochMs: now } : {}),
          ...(typeof entry.blockedReasonCode === "string"
            ? { reasonCode: entry.blockedReasonCode }
            : {}),
        })),
    });
    if (!persisted.ok) throw new CheckpointPersistenceError(persisted.reasonCode);
    if (args.linkLegacyArtifact) {
      const linked = upsertRunStateArtifact(store, {
        artifactKind: "execution_orchestration",
        pathRel: buildSuiteStatusArtifactRelPath({ projectName: args.projectName, suiteRunId }),
        suiteRunId,
        createdAtEpochMs: now,
      });
      if (!linked.ok) throw new CheckpointPersistenceError(linked.reasonCode);
    }
  } finally {
    store.close();
  }
}
