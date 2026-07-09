import type {
  RegressionExternalVerificationPhaseStatus,
  RegressionRunStatus,
  RegressionWatcherPhaseStatus,
} from "@tools-regression-execution-plan-spec/models/regression_run_artifact.model";

export type RuntimeSuiteExecutionPolicy = "stop_on_fail" | "continue_on_fail";
export type RuntimeSuitePlanOnFail = "inherit" | "stop" | "continue";

export type RuntimeSuiteRuntimeConfig = {
  requestTimeoutMs?: number;
  retryMax?: number;
};

export type RuntimeSuiteScriptPhase = "preRuntime" | "postRuntime" | "postHealthcheck" | "prePlan";

export type RuntimeSuiteScriptRef = {
  name: string;
  phase?: RuntimeSuiteScriptPhase;
};

export type RuntimeSuitePlanEntry = {
  order: number;
  planName: string;
  onFail?: RuntimeSuitePlanOnFail;
  runtimeContextName?: string;
  providedContext?: Record<string, unknown>;
};

export type RuntimeSuiteManifest = {
  executionProfile: string;
  suiteType: "regression" | "performance";
  runtimeContextName?: string;
  executionPolicy: RuntimeSuiteExecutionPolicy;
  runtimeConfig?: RuntimeSuiteRuntimeConfig;
  scriptRefs?: RuntimeSuiteScriptRef[];
  plans: RuntimeSuitePlanEntry[];
};

export type RuntimeSuitePlanRunResult = {
  order: number;
  planName: string;
  status: "executed" | "blocked" | "skipped";
  runStatus?: "pass" | "fail" | "blocked" | "in_progress";
  blockedReasonCode?: string;
  blockedReasonMeta?: Record<string, unknown>;
  runId?: string;
};

export type RuntimeSuiteRunStatus = "pass" | "fail" | "blocked" | "partial_fail" | "in_progress";

export type RuntimeSuiteBlockedResult = {
  status: "blocked";
  reasonCode: string;
  requiredUserAction: string[];
};

export type RuntimeSuiteCorrelationResult = {
  correlationSessionId: string;
  status: "ok" | "fail_closed";
  reasonCode: string;
  keyType: "traceId" | "requestId" | "messageId";
  keyValue?: string;
  contributingPlans: string[];
};

export type RuntimeSuiteProgressState =
  | "ready_for_next_plan"
  | "waiting_in_active_plan"
  | "terminal";

export type RuntimeSuiteProgressTargetSummary = {
  targetType: "watcher" | "external_verification";
  targetId?: string;
  providerType?: string;
  currentIndex: number;
  totalCount: number;
};

export type RuntimeSuiteActivePlanProgressSummary = {
  order: number;
  planName: string;
  runId?: string;
  phase: "trigger" | "watchers" | "external_verification";
  phaseStartedAt?: string;
  lastUpdatedAt?: string | null;
  triggerStatus?: RegressionRunStatus;
  watcherStatus?: RegressionWatcherPhaseStatus;
  externalVerificationStatus?: RegressionExternalVerificationPhaseStatus;
  waitingOn?: RuntimeSuiteProgressTargetSummary;
};

export type RuntimeSuiteCompletedPlanSummary = {
  order: number;
  planName: string;
  status: "executed" | "blocked";
  runStatus?: "pass" | "fail" | "blocked";
  runId?: string;
};

export type RuntimeSuiteProgressSummary = {
  progressState: RuntimeSuiteProgressState;
  totalPlanCount: number;
  completedPlanCount: number;
  remainingPlanCount: number;
  activePlan?: RuntimeSuiteActivePlanProgressSummary;
  lastCompletedPlan?: RuntimeSuiteCompletedPlanSummary;
};

export type RuntimeSuiteRunResult = {
  executionProfile: string;
  status: RuntimeSuiteRunStatus;
  reasonCode?: string;
  reasonMeta?: Record<string, unknown>;
  executionPolicy: RuntimeSuiteExecutionPolicy;
  planRuns: RuntimeSuitePlanRunResult[];
  suiteRunId?: string;
  nextPlanOrder?: number;
  completedPlanCount?: number;
  correlations?: RuntimeSuiteCorrelationResult[];
  suiteContext?: Record<string, unknown>;
  progressSummary?: RuntimeSuiteProgressSummary;
};
