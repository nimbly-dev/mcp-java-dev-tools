import { deriveNextActionCode } from "@tools-core/failure_diagnostics";
import type {
  RuntimeSuiteBlockedResult,
  RuntimeSuiteRunResult,
} from "@tools-execution-plan-spec/models/runtime_suite.model";
import type { ExecutionOrchestrationActionResult } from "../models/execution_orchestration.model";

export function blockedExecutionOrchestrationResponse(args: {
  reasonCode: string;
  reason: string;
  reasonMeta?: Record<string, unknown>;
}): ExecutionOrchestrationActionResult {
  const structuredContent: Record<string, unknown> = {
    resultType: "report",
    status: args.reasonCode,
    reasonCode: args.reasonCode,
    nextActionCode: deriveNextActionCode(args.reasonCode),
    reason: args.reason,
    ...(args.reasonMeta ? { reasonMeta: args.reasonMeta } : {}),
  };
  return toExecutionOrchestrationResponse(structuredContent);
}

export function inProgressResumeConflictResponse(args: {
  projectName: string;
  executionProfile: string;
  suite: RuntimeSuiteRunResult;
  sqliteCanonicalSuiteState: boolean;
  leaseExpiresAtEpochMs?: number;
}): ExecutionOrchestrationActionResult {
  const structuredContent: Record<string, unknown> = {
    resultType: "execution_orchestration",
    status: "in_progress",
    action: "execute",
    projectName: args.projectName,
    executionProfile: args.suite.executionProfile,
    executionPolicy: args.suite.executionPolicy,
    suiteRunId: args.suite.suiteRunId,
    ...(typeof args.leaseExpiresAtEpochMs === "number"
      ? { leaseExpiresAtEpochMs: args.leaseExpiresAtEpochMs }
      : {}),
    reasonCode: "suite_checkpoint_owner_active",
    nextActionCode: "resume_same_suite",
    ...(args.sqliteCanonicalSuiteState ? { stateSurface: "run_state" } : {}),
    planRuns: args.suite.planRuns,
    ...(typeof args.suite.nextPlanOrder === "number"
      ? { nextPlanOrder: args.suite.nextPlanOrder }
      : {}),
    ...(typeof args.suite.completedPlanCount === "number"
      ? { completedPlanCount: args.suite.completedPlanCount }
      : {}),
    ...(args.suite.progressSummary ? { progressSummary: args.suite.progressSummary } : {}),
  };
  return toExecutionOrchestrationResponse(structuredContent);
}

export function terminalResumeResponse(args: {
  projectName: string;
  executionProfile: string;
  suiteRunId: string;
  suite: RuntimeSuiteRunResult;
  sqliteCanonicalSuiteState: boolean;
}): ExecutionOrchestrationActionResult {
  const structuredContent = {
    resultType: "execution_orchestration",
    status: args.suite.status,
    action: "execute",
    projectName: args.projectName,
    executionProfile: args.suite.executionProfile,
    executionPolicy: args.suite.executionPolicy,
    suiteRunId: args.suiteRunId,
    ...(args.sqliteCanonicalSuiteState ? { stateSurface: "run_state" } : {}),
    planRuns: args.suite.planRuns,
    ...(typeof args.suite.reasonCode === "string" ? { reasonCode: args.suite.reasonCode } : {}),
    ...(args.suite.reasonMeta ? { reasonMeta: args.suite.reasonMeta } : {}),
    ...(typeof args.suite.completedPlanCount === "number"
      ? { completedPlanCount: args.suite.completedPlanCount }
      : {}),
    ...(args.suite.progressSummary ? { progressSummary: args.suite.progressSummary } : {}),
    ...(Array.isArray(args.suite.correlations) ? { correlations: args.suite.correlations } : {}),
  };
  return toExecutionOrchestrationResponse(structuredContent);
}

export function reconciledResumeResponse(args: {
  projectName: string;
  executionProfile: string;
  suiteRunId: string;
  suite: RuntimeSuiteRunResult;
}): ExecutionOrchestrationActionResult {
  const structuredContent = {
    resultType: "execution_orchestration",
    status: args.suite.status,
    action: "execute",
    projectName: args.projectName,
    executionProfile: args.executionProfile,
    suiteRunId: args.suiteRunId,
    stateSurface: "run_state",
    reasonCode: args.suite.reasonCode,
    planRuns: args.suite.planRuns,
    progressSummary: args.suite.progressSummary,
  };
  return toExecutionOrchestrationResponse(structuredContent);
}

export function finalExecutionOrchestrationResponse(args: {
  projectName: string;
  suite: RuntimeSuiteRunResult;
  sqliteCanonicalSuiteState: boolean;
  statusArtifactPath: string;
}): ExecutionOrchestrationActionResult {
  const structuredContent = {
    resultType: "execution_orchestration",
    status: args.suite.status,
    action: "execute",
    projectName: args.projectName,
    executionProfile: args.suite.executionProfile,
    executionPolicy: args.suite.executionPolicy,
    suiteRunId: args.suite.suiteRunId,
    ...(args.sqliteCanonicalSuiteState
      ? { stateSurface: "run_state" }
      : { statusArtifactPath: args.statusArtifactPath }),
    planRuns: args.suite.planRuns,
    ...(typeof args.suite.nextPlanOrder === "number"
      ? { nextPlanOrder: args.suite.nextPlanOrder }
      : {}),
    ...(typeof args.suite.completedPlanCount === "number"
      ? { completedPlanCount: args.suite.completedPlanCount }
      : {}),
    ...(typeof args.suite.reasonCode === "string" ? { reasonCode: args.suite.reasonCode } : {}),
    ...(args.suite.reasonMeta ? { reasonMeta: args.suite.reasonMeta } : {}),
    ...(args.suite.progressSummary ? { progressSummary: args.suite.progressSummary } : {}),
    ...(Array.isArray(args.suite.correlations) ? { correlations: args.suite.correlations } : {}),
  };
  return toExecutionOrchestrationResponse(structuredContent);
}

export function isSuiteBlockedResult(
  value: RuntimeSuiteRunResult | RuntimeSuiteBlockedResult,
): value is RuntimeSuiteBlockedResult {
  return "requiredUserAction" in value && !("executionProfile" in value);
}

function toExecutionOrchestrationResponse(
  structuredContent: Record<string, unknown>,
): ExecutionOrchestrationActionResult {
  return {
    content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
    structuredContent,
  };
}
