import { CONFIG_DEFAULTS } from "@tools-core/probe_defaults";
import {
  openRunStateStore,
  readProjectArtifact,
  readRegressionSuiteState,
  readRunStateCutoverStatus,
} from "@tools-feature-artifact-management";
import type { ProbeDomainConfig } from "@tools-feature-probe";
import {
  executeExecutionOrchestrationResiliencyLoop,
  resolveExecutionOrchestrationLoopPolicy,
} from "../shared/resiliency";
import type {
  ExecutionOrchestrationActionInput,
  ExecutionOrchestrationActionResult,
  ExecutionOrchestrationPassResult,
} from "../models/execution_orchestration.model";
import type { RuntimeSuiteRunResult } from "../../../spec/regression-execution-plan-spec/src/models/regression_runtime_suite.model";
import {
  CheckpointPersistenceError,
  persistSQLiteSuiteCheckpoint,
} from "../support/execution_orchestration_state";
import {
  releaseSuiteLeaseBestEffort,
  renewSuiteLease,
} from "../support/execution_orchestration_lease";
import { runtimeSuiteFromSQLiteState } from "../support/execution_orchestration_state";
import {
  blockedExecutionOrchestrationResponse,
  finalExecutionOrchestrationResponse,
  isSuiteBlockedResult,
  inProgressResumeConflictResponse,
} from "../support/execution_orchestration_result";
import {
  prepareExecutionOrchestrationResume,
  readLatestResumeState,
} from "../support/execution_orchestration_resume";
import { createSuitePassExecutor } from "../support/execution_orchestration_suite";
import { createSuiteToolInvoker } from "../support/execution_orchestration_transport";
import {
  buildSuiteStatusArtifactRelPath,
  readExecutionOrchestrationSuiteResult,
  writeExecutionOrchestrationSuiteResult,
} from "@tools-regression-suite";
import path from "node:path";
import { randomUUID } from "node:crypto";

export async function executeExecutionOrchestrationAction(
  input: ExecutionOrchestrationActionInput,
): Promise<ExecutionOrchestrationActionResult> {
  if (input.action !== "execute") {
    return blockedExecutionOrchestrationResponse({
      reasonCode: "execution_action_not_allowed",
      reason: `action '${String(input.action)}' is not permitted`,
      reasonMeta: { allowedActions: ["execute"] },
    });
  }

  const projectName = input.payload.projectName.trim();
  const executionProfile = input.payload.executionProfile.trim();
  const suiteRunId = normalizeSuiteRunId(input.payload.suiteRunId);
  const maxPlansPerCall = normalizeMaxPlansPerCall(input.payload.maxPlansPerCall);
  const checkpointOwnerId = randomUUID();
  if (!projectName) {
    return blockedExecutionOrchestrationResponse({
      reasonCode: "project_name_required",
      reason: "projectName is required",
      reasonMeta: { action: input.action },
    });
  }
  if (!executionProfile) {
    return blockedExecutionOrchestrationResponse({
      reasonCode: "execution_profile_required",
      reason: "executionProfile is required",
      reasonMeta: { action: input.action, projectName },
    });
  }

  const probeConfig = input.probeConfig ?? defaultProbeConfig();
  let priorSuite: RuntimeSuiteRunResult | null = null;
  let sqliteCanonicalSuiteState = false;
  let suiteLeaseAcquired = false;
  const releaseOwnedSuiteLease = async (): Promise<void> => {
    if (!suiteLeaseAcquired || typeof suiteRunId !== "string") return;
    suiteLeaseAcquired = false;
    await releaseSuiteLeaseBestEffort({
      workspaceRootAbs: input.workspaceRootAbs,
      projectName,
      suiteRunId,
      ownerId: checkpointOwnerId,
    });
  };
  const renewOwnedSuiteLease = async (deadlineAtEpochMs?: number): Promise<void> =>
    renewSuiteLease({
      workspaceRootAbs: input.workspaceRootAbs,
      projectName,
      suiteRunId,
      ownerId: checkpointOwnerId,
      acquired: suiteLeaseAcquired,
      deadlineAtEpochMs,
    });

  const resume =
    typeof suiteRunId === "string"
      ? await prepareExecutionOrchestrationResume({
          workspaceRootAbs: input.workspaceRootAbs,
          projectName,
          executionProfile,
          suiteRunId,
          ownerId: checkpointOwnerId,
        })
      : {
          priorSuite: null,
          sqliteCanonicalSuiteState: false,
          leaseAcquired: false,
        };
  priorSuite = resume.priorSuite;
  sqliteCanonicalSuiteState = resume.sqliteCanonicalSuiteState;
  suiteLeaseAcquired = resume.leaseAcquired;
  if (resume.response) return resume.response;

  const projectsFileAbs = path.join(
    input.workspaceRootAbs,
    ".mcpjvm",
    projectName,
    "projects.json",
  );
  const projectArtifact = await readProjectArtifact(projectsFileAbs).catch(() => ({
    ok: false as const,
    reasonCode: "project_artifact_missing" as const,
    errors: [`Create project artifact at ${projectsFileAbs}.`],
  }));
  if (!projectArtifact.ok) {
    await releaseOwnedSuiteLease();
    return blockedExecutionOrchestrationResponse({
      reasonCode: projectArtifact.reasonCode,
      reason: projectArtifact.reasonCode,
      reasonMeta: {
        projectName,
        executionProfile,
        requiredUserAction: projectArtifact.errors,
      },
    });
  }

  const workspace = projectArtifact.artifact.workspaces.find(
    (entry) => entry.projectRoot === input.workspaceRootAbs,
  );
  if (!workspace) {
    await releaseOwnedSuiteLease();
    return blockedExecutionOrchestrationResponse({
      reasonCode: "runtime_suite_missing",
      reason: "runtime_suite_missing",
      reasonMeta: {
        projectName,
        executionProfile,
        requiredUserAction: ["Workspace entry not found for current projectRoot in projects.json."],
      },
    });
  }
  const profile = (workspace.executionProfiles ?? []).find(
    (entry) => entry.executionProfile === executionProfile,
  );
  if (!profile) {
    await releaseOwnedSuiteLease();
    return blockedExecutionOrchestrationResponse({
      reasonCode: "runtime_suite_missing",
      reason: "runtime_suite_missing",
      reasonMeta: {
        projectName,
        executionProfile,
        requiredUserAction: [`Add executionProfiles entry '${executionProfile}' to projects.json.`],
      },
    });
  }
  if (profile.suiteType === "regression" && typeof suiteRunId !== "string") {
    const stateStore = await openRunStateStore({
      workspaceRootAbs: input.workspaceRootAbs,
      projectName,
    });
    if (!stateStore.ok) {
      return blockedExecutionOrchestrationResponse({
        reasonCode: stateStore.reasonCode,
        reason: stateStore.reasonCode,
        reasonMeta: { projectName, executionProfile },
      });
    }
    sqliteCanonicalSuiteState =
      readRunStateCutoverStatus({ store: stateStore }) === "cutover_complete";
    stateStore.close();
  }

  const orchestratorDefaults = workspace.defaults?.orchestrator;
  if (!orchestratorDefaults) {
    await releaseOwnedSuiteLease();
    return blockedExecutionOrchestrationResponse({
      reasonCode: "runtime_suite_missing",
      reason: "runtime_suite_missing",
      reasonMeta: {
        projectName,
        executionProfile,
        requiredUserAction: ["Add workspaces[].defaults.orchestrator to projects.json."],
      },
    });
  }

  const invokeSuiteTool = createSuiteToolInvoker({ probeConfig });
  const loopPolicy = resolveExecutionOrchestrationLoopPolicy(orchestratorDefaults);
  const enableOuterResiliencyLoop =
    typeof maxPlansPerCall !== "number" && loopPolicy.effectiveTimeoutBudgetMs > 0;
  const maxPlansPerPass = enableOuterResiliencyLoop ? 1 : maxPlansPerCall;
  const executeSuitePass = createSuitePassExecutor({
    suiteType: profile.suiteType,
    workspaceRootAbs: input.workspaceRootAbs,
    projectName,
    executionProfile,
    maxPlansPerPass,
    mcpInvoke: invokeSuiteTool,
    renewSuiteLease: renewOwnedSuiteLease,
  });

  let suite: ExecutionOrchestrationPassResult;
  try {
    suite = enableOuterResiliencyLoop
      ? await executeExecutionOrchestrationResiliencyLoop({
          projectName,
          executionProfile,
          defaults: orchestratorDefaults,
          ...(typeof suiteRunId === "string" ? { initialSuiteRunId: suiteRunId } : {}),
          ...(priorSuite ? { initialPriorSuite: priorSuite } : {}),
          executePass: executeSuitePass,
          persistSuite: async (nextSuite) => {
            if (!sqliteCanonicalSuiteState) {
              await writeExecutionOrchestrationSuiteResult({
                workspaceRootAbs: input.workspaceRootAbs,
                projectName,
                suite: nextSuite,
              });
            }
            if (profile.suiteType === "regression") {
              await persistSQLiteSuiteCheckpoint({
                workspaceRootAbs: input.workspaceRootAbs,
                projectName,
                suite: nextSuite,
                ownerId: checkpointOwnerId,
                linkLegacyArtifact: !sqliteCanonicalSuiteState,
              });
            }
          },
          readPersistedSuite: async (nextSuiteRunId) => {
            if (sqliteCanonicalSuiteState) {
              const store = await openRunStateStore({
                workspaceRootAbs: input.workspaceRootAbs,
                projectName,
              });
              if (!store.ok) return null;
              try {
                return runtimeSuiteFromSQLiteState({
                  state: readRegressionSuiteState({ store, suiteRunId: nextSuiteRunId }),
                });
              } finally {
                store.close();
              }
            }
            return readExecutionOrchestrationSuiteResult({
              workspaceRootAbs: input.workspaceRootAbs,
              projectName,
              suiteRunId: nextSuiteRunId,
            });
          },
        })
      : await executeSuitePass(
          {
            ...(typeof suiteRunId === "string" ? { suiteRunId } : {}),
            ...(priorSuite ? { priorSuite } : {}),
          },
          loopPolicy.effectiveTimeoutBudgetMs,
        );
  } catch (error) {
    const persistenceReason =
      error instanceof CheckpointPersistenceError
        ? error.reasonCode
        : error instanceof Error
          ? error.message
          : String(error);
    if (isResumeConflictPersistenceReason(persistenceReason) && typeof suiteRunId === "string") {
      const latest = await readLatestResumeState({
        workspaceRootAbs: input.workspaceRootAbs,
        projectName,
        suiteRunId,
      });
      if (latest.suite?.status === "in_progress") {
        await releaseOwnedSuiteLease();
        return inProgressResumeConflictResponse({
          projectName,
          executionProfile,
          suite: latest.suite,
          sqliteCanonicalSuiteState: latest.sqliteCanonical,
          ...(typeof latest.leaseExpiresAtEpochMs === "number"
            ? { leaseExpiresAtEpochMs: latest.leaseExpiresAtEpochMs }
            : {}),
        });
      }
    }
    await releaseOwnedSuiteLease();
    if (error instanceof CheckpointPersistenceError) {
      return blockedExecutionOrchestrationResponse({
        reasonCode: error.reasonCode,
        reason: error.reasonCode,
        reasonMeta: { projectName, executionProfile },
      });
    }
    throw error;
  }

  if (isSuiteBlockedResult(suite)) {
    await releaseOwnedSuiteLease();
    return blockedExecutionOrchestrationResponse({
      reasonCode: suite.reasonCode,
      reason: suite.reasonCode,
      reasonMeta: {
        projectName,
        executionProfile,
        requiredUserAction: suite.requiredUserAction,
      },
    });
  }

  if (!enableOuterResiliencyLoop) {
    try {
      if (!sqliteCanonicalSuiteState) {
        await writeExecutionOrchestrationSuiteResult({
          workspaceRootAbs: input.workspaceRootAbs,
          projectName,
          suite,
        });
      }
      if (profile.suiteType === "regression") {
        await persistSQLiteSuiteCheckpoint({
          workspaceRootAbs: input.workspaceRootAbs,
          projectName,
          suite,
          ownerId: checkpointOwnerId,
          linkLegacyArtifact: !sqliteCanonicalSuiteState,
        });
      }
    } catch (error) {
      await releaseOwnedSuiteLease();
      if (error instanceof CheckpointPersistenceError) {
        return blockedExecutionOrchestrationResponse({
          reasonCode: error.reasonCode,
          reason: error.reasonCode,
          reasonMeta: { projectName, executionProfile },
        });
      }
      throw error;
    }
  }

  if (profile.suiteType === "regression" && typeof suite.suiteRunId === "string") {
    await releaseSuiteLeaseBestEffort({
      workspaceRootAbs: input.workspaceRootAbs,
      projectName,
      suiteRunId: suite.suiteRunId,
      ownerId: checkpointOwnerId,
    });
    suiteLeaseAcquired = false;
  }
  return finalExecutionOrchestrationResponse({
    projectName,
    suite,
    sqliteCanonicalSuiteState,
    statusArtifactPath: buildSuiteStatusArtifactRelPath({
      projectName,
      suiteRunId: String(suite.suiteRunId),
    }),
  });
}

function normalizeSuiteRunId(value: string | undefined): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value.trim() : undefined;
}

function normalizeMaxPlansPerCall(value: number | undefined): number | undefined {
  return typeof value === "number" && Number.isInteger(value) ? value : undefined;
}

function defaultProbeConfig(): ProbeDomainConfig {
  return {
    probeBaseUrl: "",
    probeStatusPath: CONFIG_DEFAULTS.PROBE_STATUS_PATH,
    probeResetPath: CONFIG_DEFAULTS.PROBE_RESET_PATH,
    probeActuatePath: CONFIG_DEFAULTS.PROBE_ACTUATE_PATH,
    probeCapturePath: CONFIG_DEFAULTS.PROBE_CAPTURE_PATH,
    probeProfilerPath: CONFIG_DEFAULTS.PROBE_PROFILER_PATH,
    probeWaitMaxRetries: CONFIG_DEFAULTS.PROBE_WAIT_MAX_RETRIES,
    probeWaitUnreachableRetryEnabled: CONFIG_DEFAULTS.PROBE_WAIT_UNREACHABLE_RETRY_ENABLED,
    probeWaitUnreachableMaxRetries: CONFIG_DEFAULTS.PROBE_WAIT_UNREACHABLE_MAX_RETRIES,
  } satisfies ProbeDomainConfig;
}

function isResumeConflictPersistenceReason(reason: string): boolean {
  return reason === "suite_checkpoint_stale_revision" || reason === "watcher_attempt_non_monotonic";
}
