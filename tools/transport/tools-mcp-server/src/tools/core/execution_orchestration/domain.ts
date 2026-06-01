import { loadConfigFromEnvAndArgs } from "@/config/server-config";
import { createProbeDomain } from "@/tools/core/probe/domain";
import { transportExecuteDomain } from "@/tools/core/transport_execute/domain";
import { deriveNextActionCode } from "@/utils/failure_diagnostics.util";
import { executeRegressionRuntimeSuite } from "@tools-regression-execution-plan-spec/regression_runtime_suite_executor.util";

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

  const suite = await executeRegressionRuntimeSuite({
    workspaceRootAbs: input.workspaceRootAbs,
    projectName,
    executionProfile,
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
        return { structuredContent: result as Record<string, unknown> };
      }
      if (toolName === "probe_wait_for_hit") {
        const result = await probeDomain.waitForHit(toolInput as Parameters<typeof probeDomain.waitForHit>[0]);
        return { structuredContent: result as Record<string, unknown> };
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

  const structuredContent = {
    resultType: "execution_orchestration",
    status: suite.status,
    action: "execute",
    projectName,
    executionProfile: suite.executionProfile,
    executionPolicy: suite.executionPolicy,
    planRuns: suite.planRuns,
  };
  return {
    content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
    structuredContent,
  };
}
