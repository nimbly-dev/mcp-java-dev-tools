import { loadConfigFromEnvAndArgs } from "@/config/server-config";
import { createProbeDomain } from "@/tools/core/probe/domain";
import { transportExecuteDomain } from "@/tools/core/transport_execute/domain";
import { deriveNextActionCode } from "@/utils/failure_diagnostics.util";
import {
  buildSuiteStatusArtifactRelPath,
  executeRegressionRuntimeSuite,
  readExecutionOrchestrationSuiteResult,
  writeExecutionOrchestrationSuiteResult,
} from "@tools-regression-execution-plan-spec/regression_runtime_suite_executor.util";

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
    probeActuatePath: "/__probe/actuate",
    probeCapturePath: cfg.probeCapturePath,
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
        ...(typeof priorSuite.completedPlanCount === "number"
          ? { completedPlanCount: priorSuite.completedPlanCount }
          : {}),
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

  const suite = await executeRegressionRuntimeSuite({
    workspaceRootAbs: input.workspaceRootAbs,
    projectName,
    executionProfile,
    ...(typeof suiteRunId === "string" ? { suiteRunId } : {}),
    ...(typeof maxPlansPerCall === "number" ? { maxPlansPerCall } : {}),
    ...(priorSuite && Array.isArray(priorSuite.planRuns) ? { priorPlanRuns: priorSuite.planRuns } : {}),
    ...(priorSuite && typeof priorSuite.nextPlanOrder === "number"
      ? { startPlanOrder: priorSuite.nextPlanOrder }
      : {}),
    mcpInvoke: async ({ toolName, input: toolInput }) => {
      if (toolName === "transport_execute") {
        const result = await transportExecuteDomain({
          protocol: "http",
          request: toolInput.request as Record<string, unknown>,
          wrappedOnly: toolInput.wrappedOnly !== false,
          allowNonWrappedExecutable: cfg.probeRegistry?.allowNonWrappedExecutable ?? false,
        });
        return { structuredContent: result.structuredContent };
      }
      if (toolName === "probe_reset") {
        const result = await probeDomain.reset(toolInput as Parameters<typeof probeDomain.reset>[0]);
        return { structuredContent: result.structuredContent as Record<string, unknown> };
      }
      if (toolName === "probe_wait_for_hit") {
        const result = await probeDomain.waitForHit(toolInput as Parameters<typeof probeDomain.waitForHit>[0]);
        return { structuredContent: result.structuredContent as Record<string, unknown> };
      }
      return {
        structuredContent: {
          status: "blocked_invalid",
          reasonCode: "toolchain_unavailable",
          requiredUserAction: [`Unsupported suite tool invocation: ${toolName}`],
        },
      };
    },
  });

  if ("reasonCode" in suite && suite.status === "blocked") {
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

  await writeExecutionOrchestrationSuiteResult({
    workspaceRootAbs: input.workspaceRootAbs,
    projectName,
    suite,
  });

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
    ...(Array.isArray(suite.correlations) ? { correlations: suite.correlations } : {}),
  };
  return {
    content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
    structuredContent,
  };
}
