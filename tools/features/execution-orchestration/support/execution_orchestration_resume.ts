import {
  acquireRegressionSuiteLease,
  openRunStateStore,
  readRegressionSuiteCheckpoint,
  readRegressionSuiteState,
  readRunStateCutoverStatus,
  reconcileExpiredActiveState,
} from "@tools-feature-artifact-management";
import { readExecutionOrchestrationSuiteResult } from "@tools-regression-suite";
import type { RuntimeSuiteRunResult } from "../../../spec/regression-execution-plan-spec/src/models/regression_runtime_suite.model";
import type { ExecutionOrchestrationActionResult } from "../models/execution_orchestration.model";
import {
  inProgressResumeConflictResponse,
  reconciledResumeResponse,
  terminalResumeResponse,
  blockedExecutionOrchestrationResponse,
} from "./execution_orchestration_result";
import { releaseSuiteLeaseBestEffort } from "./execution_orchestration_lease";
import { runtimeSuiteFromSQLiteState } from "./execution_orchestration_state";

export type ResumePreparation = {
  priorSuite: RuntimeSuiteRunResult | null;
  sqliteCanonicalSuiteState: boolean;
  leaseAcquired: boolean;
  response?: ExecutionOrchestrationActionResult;
};

type LatestResumeState = {
  suite: RuntimeSuiteRunResult | null;
  sqliteCanonical: boolean;
  leaseExpiresAtEpochMs?: number;
};

export async function prepareExecutionOrchestrationResume(args: {
  workspaceRootAbs: string;
  projectName: string;
  executionProfile: string;
  suiteRunId: string;
  ownerId: string;
}): Promise<ResumePreparation> {
  const stateStore = await openRunStateStore({
    workspaceRootAbs: args.workspaceRootAbs,
    projectName: args.projectName,
  });
  if (!stateStore.ok) {
    return withResponse({
      reasonCode: stateStore.reasonCode,
      reason: stateStore.reasonCode,
      reasonMeta: {
        projectName: args.projectName,
        executionProfile: args.executionProfile,
        suiteRunId: args.suiteRunId,
      },
    });
  }

  const checkpoint = readRegressionSuiteCheckpoint({
    store: stateStore,
    suiteRunId: args.suiteRunId,
  });
  if (!checkpoint) {
    stateStore.close();
    return withResponse({
      reasonCode: "suite_resume_evidence_missing",
      reason: "suite_resume_evidence_missing",
      reasonMeta: resumeReasonMeta(args),
    });
  }
  if (checkpoint.executionProfile !== args.executionProfile) {
    stateStore.close();
    return withResponse({
      reasonCode: "suite_resume_identity_mismatch",
      reason: "suite_resume_identity_mismatch",
      reasonMeta: resumeReasonMeta(args),
    });
  }

  const lease = acquireRegressionSuiteLease({
    store: stateStore,
    suiteRunId: args.suiteRunId,
    ownerId: args.ownerId,
    nowEpochMs: Date.now(),
    leaseDurationMs: 30_000,
  });
  const sqliteCanonicalSuiteState =
    readRunStateCutoverStatus({ store: stateStore }) === "cutover_complete";
  const sqliteState = sqliteCanonicalSuiteState
    ? readRegressionSuiteState({ store: stateStore, suiteRunId: args.suiteRunId })
    : null;
  if (!lease.ok) {
    if (lease.reasonCode === "suite_checkpoint_owner_active") {
      const latestSqliteState = readRegressionSuiteState({
        store: stateStore,
        suiteRunId: args.suiteRunId,
      });
      stateStore.close();
      const latestSuite = latestSqliteState
        ? runtimeSuiteFromSQLiteState({ state: latestSqliteState })
        : await readExecutionOrchestrationSuiteResult({
            workspaceRootAbs: args.workspaceRootAbs,
            projectName: args.projectName,
            suiteRunId: args.suiteRunId,
          });
      if (latestSuite?.status === "in_progress") {
        return {
          priorSuite: null,
          sqliteCanonicalSuiteState,
          leaseAcquired: false,
          response: inProgressResumeConflictResponse({
            projectName: args.projectName,
            executionProfile: args.executionProfile,
            suite: latestSuite,
            sqliteCanonicalSuiteState,
            ...(typeof latestSqliteState?.checkpoint.leaseExpiresAtEpochMs === "number"
              ? { leaseExpiresAtEpochMs: latestSqliteState.checkpoint.leaseExpiresAtEpochMs }
              : typeof lease.reasonMeta?.leaseExpiresAtEpochMs === "number"
                ? { leaseExpiresAtEpochMs: lease.reasonMeta.leaseExpiresAtEpochMs }
                : {}),
          }),
        };
      }
    } else {
      stateStore.close();
    }
    return withResponse({
      reasonCode: lease.reasonCode,
      reason: lease.reasonCode,
      reasonMeta: resumeReasonMeta(args),
      sqliteCanonicalSuiteState,
    });
  }

  stateStore.close();
  let priorSuite = sqliteCanonicalSuiteState
    ? runtimeSuiteFromSQLiteState({ state: sqliteState })
    : await readExecutionOrchestrationSuiteResult({
        workspaceRootAbs: args.workspaceRootAbs,
        projectName: args.projectName,
        suiteRunId: args.suiteRunId,
      });
  if (!priorSuite) {
    await releaseSuiteLeaseBestEffort({
      workspaceRootAbs: args.workspaceRootAbs,
      projectName: args.projectName,
      suiteRunId: args.suiteRunId,
      ownerId: args.ownerId,
    });
    return withResponse({
      reasonCode: "suite_resume_evidence_missing",
      reason: "suite_resume_evidence_missing",
      reasonMeta: resumeReasonMeta(args),
      sqliteCanonicalSuiteState,
    });
  }
  if (priorSuite.executionProfile !== args.executionProfile) {
    await releaseSuiteLeaseBestEffort({
      workspaceRootAbs: args.workspaceRootAbs,
      projectName: args.projectName,
      suiteRunId: args.suiteRunId,
      ownerId: args.ownerId,
    });
    return withResponse({
      reasonCode: "suite_progress_mismatch",
      reason: "suite_progress_mismatch",
      reasonMeta: resumeReasonMeta(args),
      sqliteCanonicalSuiteState,
    });
  }
  if (priorSuite.status !== "in_progress") {
    await releaseSuiteLeaseBestEffort({
      workspaceRootAbs: args.workspaceRootAbs,
      projectName: args.projectName,
      suiteRunId: args.suiteRunId,
      ownerId: args.ownerId,
    });
    return {
      priorSuite: null,
      sqliteCanonicalSuiteState,
      leaseAcquired: false,
      response: terminalResumeResponse({
        projectName: args.projectName,
        executionProfile: args.executionProfile,
        suiteRunId: args.suiteRunId,
        suite: priorSuite,
        sqliteCanonicalSuiteState,
      }),
    };
  }
  if (typeof priorSuite.nextPlanOrder !== "number") {
    await releaseSuiteLeaseBestEffort({
      workspaceRootAbs: args.workspaceRootAbs,
      projectName: args.projectName,
      suiteRunId: args.suiteRunId,
      ownerId: args.ownerId,
    });
    return withResponse({
      reasonCode: "suite_progress_invalid",
      reason: "suite_progress_invalid",
      reasonMeta: resumeReasonMeta(args),
      sqliteCanonicalSuiteState,
    });
  }

  const reconciled = await reconcileExpiredActiveState({
    workspaceRootAbs: args.workspaceRootAbs,
    projectName: args.projectName,
    executionProfile: args.executionProfile,
    suiteRunId: args.suiteRunId,
    ownerId: args.ownerId,
    nowEpochMs: Date.now(),
  });
  if (!reconciled.ok) {
    await releaseSuiteLeaseBestEffort({
      workspaceRootAbs: args.workspaceRootAbs,
      projectName: args.projectName,
      suiteRunId: args.suiteRunId,
      ownerId: args.ownerId,
    });
    return withResponse({
      reasonCode: reconciled.reasonCode,
      reason: reconciled.reason,
      ...(reconciled.reasonMeta ? { reasonMeta: reconciled.reasonMeta } : {}),
      sqliteCanonicalSuiteState,
    });
  }
  if (reconciled.reconciled) {
    return {
      priorSuite: null,
      sqliteCanonicalSuiteState,
      leaseAcquired: false,
      response: reconciledResumeResponse({
        projectName: args.projectName,
        executionProfile: args.executionProfile,
        suiteRunId: args.suiteRunId,
        suite: reconciled.suite as RuntimeSuiteRunResult,
      }),
    };
  }

  return { priorSuite, sqliteCanonicalSuiteState, leaseAcquired: true };
}

export async function readLatestResumeState(args: {
  workspaceRootAbs: string;
  projectName: string;
  suiteRunId: string;
}): Promise<LatestResumeState> {
  const store = await openRunStateStore({
    workspaceRootAbs: args.workspaceRootAbs,
    projectName: args.projectName,
  });
  if (!store.ok) return { suite: null, sqliteCanonical: false };
  try {
    const sqliteCanonical = readRunStateCutoverStatus({ store }) === "cutover_complete";
    const sqliteState = readRegressionSuiteState({ store, suiteRunId: args.suiteRunId });
    if (sqliteState) {
      return {
        suite: runtimeSuiteFromSQLiteState({ state: sqliteState }),
        sqliteCanonical,
        ...(typeof sqliteState.checkpoint.leaseExpiresAtEpochMs === "number"
          ? { leaseExpiresAtEpochMs: sqliteState.checkpoint.leaseExpiresAtEpochMs }
          : {}),
      };
    }
    return {
      suite: await readExecutionOrchestrationSuiteResult({
        workspaceRootAbs: args.workspaceRootAbs,
        projectName: args.projectName,
        suiteRunId: args.suiteRunId,
      }),
      sqliteCanonical,
    };
  } finally {
    store.close();
  }
}

function withResponse(args: {
  reasonCode: string;
  reason: string;
  reasonMeta?: Record<string, unknown>;
  sqliteCanonicalSuiteState?: boolean;
}): ResumePreparation {
  return {
    priorSuite: null,
    sqliteCanonicalSuiteState: args.sqliteCanonicalSuiteState ?? false,
    leaseAcquired: false,
    response: blockedExecutionOrchestrationResponse({
      reasonCode: args.reasonCode,
      reason: args.reason,
      ...(args.reasonMeta ? { reasonMeta: args.reasonMeta } : {}),
    }),
  };
}

function resumeReasonMeta(args: {
  projectName: string;
  executionProfile: string;
  suiteRunId: string;
}): Record<string, unknown> {
  return {
    projectName: args.projectName,
    executionProfile: args.executionProfile,
    suiteRunId: args.suiteRunId,
  };
}
