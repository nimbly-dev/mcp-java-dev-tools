import { loadConfigFromEnvAndArgs } from "@/config/server-config";
import { CONFIG_DEFAULTS } from "@tools-core/probe_defaults";
import { createProbeDomain } from "@tools-feature-probe";
import {
  executeExecutionOrchestrationResiliencyLoop,
  resolveExecutionOrchestrationLoopPolicy,
} from "./shared/resiliency";
import { deriveNextActionCode } from "@tools-core/failure_diagnostics";
import { executeHttpTransportRequest } from "@/utils/transport_execute_http.util";
import { readProjectArtifact } from "@tools-project-artifact-spec/project_artifact.util";
import {
  buildSuiteStatusArtifactRelPath,
  executeRegressionRuntimeSuite,
  readExecutionOrchestrationSuiteResult,
  writeExecutionOrchestrationSuiteResult,
} from "@tools-regression-suite";
import { executePerformanceRuntimeSuite } from "@tools-feature-performance-suite";
import type {
  RuntimeSuiteBlockedResult,
  RuntimeSuiteRunResult,
} from "../../spec/regression-execution-plan-spec/src/models/regression_runtime_suite.model";
import path from "node:path";

function blockedResponse(args: {
  reasonCode: string;
  reason: string;
  reasonMeta?: Record<string, unknown>;
}) {
  const structuredContent: Record<string, unknown> = {
    resultType: "report",
    status: args.reasonCode,
    reasonCode: args.reasonCode,
    nextActionCode: deriveNextActionCode(args.reasonCode),
    reason: args.reason,
    ...(args.reasonMeta ? { reasonMeta: args.reasonMeta } : {}),
  };
  return {
    content: [{ type: "text" as const, text: JSON.stringify(structuredContent, null, 2) }],
    structuredContent,
  };
}

function isSuiteBlockedResult(
  value: RuntimeSuiteRunResult | RuntimeSuiteBlockedResult,
): value is RuntimeSuiteBlockedResult {
  return "requiredUserAction" in value && !("executionProfile" in value);
}

export async function executionOrchestrationDomain(input: {
  workspaceRootAbs: string;
  action: "execute";
  payload: {
    projectName: string;
    executionProfile: string;
    suiteRunId?: string;
    maxPlansPerCall?: number;
  };
}): Promise<{
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
}> {
  if (input.action !== "execute") {
    return blockedResponse({
      reasonCode: "execution_action_not_allowed",
      reason: `action '${String(input.action)}' is not permitted`,
      reasonMeta: { allowedActions: ["execute"] },
    });
  }

  const projectName = input.payload.projectName.trim();
  const executionProfile = input.payload.executionProfile.trim();
  const suiteRunId =
    typeof input.payload.suiteRunId === "string" && input.payload.suiteRunId.trim().length > 0
      ? input.payload.suiteRunId.trim()
      : undefined;
  const maxPlansPerCall =
    typeof input.payload.maxPlansPerCall === "number" && Number.isInteger(input.payload.maxPlansPerCall)
      ? input.payload.maxPlansPerCall
      : undefined;
  if (!projectName) {
    return blockedResponse({
      reasonCode: "project_name_required",
      reason: "projectName is required",
      reasonMeta: { action: input.action },
    });
  }
  if (!executionProfile) {
    return blockedResponse({
      reasonCode: "execution_profile_required",
      reason: "executionProfile is required",
      reasonMeta: { action: input.action, projectName },
    });
  }

  const cfg = loadConfigFromEnvAndArgs(process.argv);
  const probeDomain = createProbeDomain({
    probeBaseUrl: cfg.probeBaseUrl,
    probeStatusPath: cfg.probeStatusPath,
    probeResetPath: cfg.probeResetPath,
    probeActuatePath: CONFIG_DEFAULTS.PROBE_ACTUATE_PATH,
    probeCapturePath: cfg.probeCapturePath,
    probeProfilerPath: CONFIG_DEFAULTS.PROBE_PROFILER_PATH,
    probeWaitMaxRetries: cfg.probeWaitMaxRetries,
    probeWaitUnreachableRetryEnabled: cfg.probeWaitUnreachableRetryEnabled,
    probeWaitUnreachableMaxRetries: cfg.probeWaitUnreachableMaxRetries,
    ...(cfg.probeRegistry ? { getProbeRegistry: () => cfg.probeRegistry } : {}),
  });

  let priorSuite:
    | Awaited<ReturnType<typeof readExecutionOrchestrationSuiteResult>>
    | null = null;
  if (typeof suiteRunId === "string") {
    priorSuite = await readExecutionOrchestrationSuiteResult({
      workspaceRootAbs: input.workspaceRootAbs,
      projectName,
      suiteRunId,
    });
    if (!priorSuite) {
      return blockedResponse({
        reasonCode: "suite_progress_missing",
        reason: "suite_progress_missing",
        reasonMeta: { projectName, executionProfile, suiteRunId },
      });
    }
    if (priorSuite.executionProfile !== executionProfile) {
      return blockedResponse({
        reasonCode: "suite_progress_mismatch",
        reason: "suite_progress_mismatch",
        reasonMeta: { projectName, executionProfile, suiteRunId },
      });
    }
    if (priorSuite.status !== "in_progress") {
      const structuredContent = {
        resultType: "execution_orchestration",
        status: priorSuite.status,
        action: "execute",
        projectName,
        executionProfile: priorSuite.executionProfile,
        executionPolicy: priorSuite.executionPolicy,
        suiteRunId,
        planRuns: priorSuite.planRuns,
        ...(typeof priorSuite.reasonCode === "string" ? { reasonCode: priorSuite.reasonCode } : {}),
        ...(priorSuite.reasonMeta ? { reasonMeta: priorSuite.reasonMeta } : {}),
        ...(typeof priorSuite.completedPlanCount === "number"
          ? { completedPlanCount: priorSuite.completedPlanCount }
          : {}),
        ...(priorSuite.progressSummary ? { progressSummary: priorSuite.progressSummary } : {}),
        ...(Array.isArray(priorSuite.correlations) ? { correlations: priorSuite.correlations } : {}),
      };
      return {
        content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
        structuredContent,
      };
    }
    if (typeof priorSuite.nextPlanOrder !== "number") {
      return blockedResponse({
        reasonCode: "suite_progress_invalid",
        reason: "suite_progress_invalid",
        reasonMeta: { projectName, executionProfile, suiteRunId },
      });
    }
  }

  const projectsFileAbs = path.join(input.workspaceRootAbs, ".mcpjvm", projectName, "projects.json");
  const projectArtifact = await readProjectArtifact(projectsFileAbs).catch(() => ({
    ok: false as const,
    reasonCode: "project_artifact_missing" as const,
    errors: [`Create project artifact at ${projectsFileAbs}.`],
  }));
  if (!projectArtifact.ok) {
    return blockedResponse({
      reasonCode: projectArtifact.reasonCode,
      reason: projectArtifact.reasonCode,
      reasonMeta: {
        projectName,
        executionProfile,
        requiredUserAction: projectArtifact.errors,
      },
    });
  }
  const workspace = projectArtifact.artifact.workspaces.find((entry) => entry.projectRoot === input.workspaceRootAbs);
  if (!workspace) {
    return blockedResponse({
      reasonCode: "runtime_suite_missing",
      reason: "runtime_suite_missing",
      reasonMeta: {
        projectName,
        executionProfile,
        requiredUserAction: ["Workspace entry not found for current projectRoot in projects.json."],
      },
    });
  }
  const profile = (workspace.executionProfiles ?? []).find((entry) => entry.executionProfile === executionProfile);
  if (!profile) {
    return blockedResponse({
      reasonCode: "runtime_suite_missing",
      reason: "runtime_suite_missing",
      reasonMeta: {
        projectName,
        executionProfile,
        requiredUserAction: [`Add executionProfiles entry '${executionProfile}' to projects.json.`],
      },
    });
  }
  const orchestratorDefaults = workspace.defaults?.orchestrator;
  if (!orchestratorDefaults) {
    return blockedResponse({
      reasonCode: "runtime_suite_missing",
      reason: "runtime_suite_missing",
      reasonMeta: {
        projectName,
        executionProfile,
        requiredUserAction: ["Add workspaces[].defaults.orchestrator to projects.json."],
      },
    });
  }

  const invokeSuiteTool = async ({ toolName, input: toolInput }: { toolName: string; input: Record<string, unknown> }) => {
    if (toolName === "transport_execute") {
      if (toolInput.wrappedOnly !== false && (cfg.probeRegistry?.allowNonWrappedExecutable ?? false)) {
        return {
          structuredContent: {
            status: "blocked_invalid",
            reasonCode: "wrapper_policy_violation",
            requiredUserAction: [
              "Disable non-wrapped executable transport in probe registry or do not require wrappedOnly execution.",
            ],
          },
        };
      }
      return {
        structuredContent: await executeHttpTransportRequest({
          request: toolInput.request as Record<string, unknown>,
          includeBody: true,
        }),
      };
    }
    if (toolName === "probe") {
      const action = toolInput.action;
      const probeInput =
        typeof toolInput.input === "object" && toolInput.input !== null && !Array.isArray(toolInput.input)
          ? (toolInput.input as Record<string, unknown>)
          : undefined;
      if (action === "reset" && probeInput) {
        const result = await probeDomain.reset(probeInput as Parameters<typeof probeDomain.reset>[0]);
        return { structuredContent: result.structuredContent as Record<string, unknown> };
      }
      if (action === "wait_for_hit" && probeInput) {
        const result = await probeDomain.waitForHit(probeInput as Parameters<typeof probeDomain.waitForHit>[0]);
        return { structuredContent: result.structuredContent as Record<string, unknown> };
      }
      if (action === "profiler" && probeInput) {
        const result = await probeDomain.profiler(probeInput as Parameters<typeof probeDomain.profiler>[0]);
        return { structuredContent: result.structuredContent as Record<string, unknown> };
      }
    }
    return {
      structuredContent: {
        status: "blocked_invalid",
        reasonCode: "toolchain_unavailable",
        requiredUserAction: [`Unsupported suite tool invocation: ${toolName}`],
      },
    };
  };

  const loopPolicy = resolveExecutionOrchestrationLoopPolicy(orchestratorDefaults);
  const enableOuterResiliencyLoop =
    typeof maxPlansPerCall !== "number" &&
    loopPolicy.effectiveTimeoutBudgetMs > 0;
  const maxPlansPerPass = enableOuterResiliencyLoop ? 1 : maxPlansPerCall;

  const executeSuitePass = async (state: {
    suiteRunId?: string;
    priorSuite?: NonNullable<typeof priorSuite> | null;
  }, remainingBudgetMs: number) =>
    profile.suiteType === "performance"
      ? await executePerformanceRuntimeSuite({
          workspaceRootAbs: input.workspaceRootAbs,
          projectName,
          executionProfile,
          ...(typeof state.suiteRunId === "string" ? { suiteRunId: state.suiteRunId } : {}),
          ...(typeof maxPlansPerPass === "number" ? { maxPlansPerCall: maxPlansPerPass } : {}),
          ...(state.priorSuite && Array.isArray(state.priorSuite.planRuns) ? { priorPlanRuns: state.priorSuite.planRuns } : {}),
          ...(state.priorSuite && typeof state.priorSuite.suiteContext === "object" && state.priorSuite.suiteContext !== null
            ? { priorSuiteContext: state.priorSuite.suiteContext }
            : {}),
          ...(state.priorSuite && typeof state.priorSuite.nextPlanOrder === "number"
            ? { startPlanOrder: state.priorSuite.nextPlanOrder }
            : {}),
          mcpInvoke: invokeSuiteTool,
        })
      : await executeRegressionRuntimeSuite({
          workspaceRootAbs: input.workspaceRootAbs,
          projectName,
          executionProfile,
          ...(typeof state.suiteRunId === "string" ? { suiteRunId: state.suiteRunId } : {}),
          ...(typeof maxPlansPerPass === "number" ? { maxPlansPerCall: maxPlansPerPass } : {}),
          ...(state.priorSuite && Array.isArray(state.priorSuite.planRuns) ? { priorPlanRuns: state.priorSuite.planRuns } : {}),
          ...(state.priorSuite && typeof state.priorSuite.suiteContext === "object" && state.priorSuite.suiteContext !== null
            ? { priorSuiteContext: state.priorSuite.suiteContext }
            : {}),
          ...(state.priorSuite && typeof state.priorSuite.nextPlanOrder === "number"
            ? { startPlanOrder: state.priorSuite.nextPlanOrder }
            : {}),
          mcpInvoke: invokeSuiteTool,
          orchestrationTimeoutBudgetMs: remainingBudgetMs,
        });

  const suite = enableOuterResiliencyLoop
    ? await executeExecutionOrchestrationResiliencyLoop({
        projectName,
        executionProfile,
        defaults: orchestratorDefaults,
        ...(typeof suiteRunId === "string" ? { initialSuiteRunId: suiteRunId } : {}),
        ...(priorSuite ? { initialPriorSuite: priorSuite } : {}),
        executePass: executeSuitePass,
        persistSuite: async (nextSuite) => {
          await writeExecutionOrchestrationSuiteResult({
            workspaceRootAbs: input.workspaceRootAbs,
            projectName,
            suite: nextSuite,
          });
        },
        readPersistedSuite: async (nextSuiteRunId) =>
          await readExecutionOrchestrationSuiteResult({
            workspaceRootAbs: input.workspaceRootAbs,
            projectName,
            suiteRunId: nextSuiteRunId,
          }),
      })
    : await executeSuitePass({
        ...(typeof suiteRunId === "string" ? { suiteRunId } : {}),
        ...(priorSuite ? { priorSuite } : {}),
      }, loopPolicy.effectiveTimeoutBudgetMs);

  if (isSuiteBlockedResult(suite)) {
    return blockedResponse({
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
    await writeExecutionOrchestrationSuiteResult({
      workspaceRootAbs: input.workspaceRootAbs,
      projectName,
      suite,
    });
  }

  const structuredContent = {
    resultType: "execution_orchestration",
    status: suite.status,
    action: "execute",
    projectName,
    executionProfile: suite.executionProfile,
    executionPolicy: suite.executionPolicy,
    suiteRunId: suite.suiteRunId,
    statusArtifactPath: buildSuiteStatusArtifactRelPath({
      projectName,
      suiteRunId: String(suite.suiteRunId),
    }),
    planRuns: suite.planRuns,
    ...(typeof suite.nextPlanOrder === "number" ? { nextPlanOrder: suite.nextPlanOrder } : {}),
    ...(typeof suite.completedPlanCount === "number" ? { completedPlanCount: suite.completedPlanCount } : {}),
    ...(typeof suite.reasonCode === "string" ? { reasonCode: suite.reasonCode } : {}),
    ...(suite.reasonMeta ? { reasonMeta: suite.reasonMeta } : {}),
    ...(suite.progressSummary ? { progressSummary: suite.progressSummary } : {}),
    ...(Array.isArray(suite.correlations) ? { correlations: suite.correlations } : {}),
  };
  return {
    content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
    structuredContent,
  };
}
