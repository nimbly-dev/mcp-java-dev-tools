import type { ExecutionOrchestrationRequest } from "@tools-contracts/execution-orchestration";
import type { ProbeDomainConfig } from "@tools-feature-probe";
import { executeExecutionOrchestrationAction } from "./execute_execution_orchestration.action";

export type ExecutionOrchestrationActionMap = Readonly<Record<"execute", typeof executeExecutionOrchestrationAction>>;

export function dispatchExecutionOrchestrationAction(args: {
  workspaceRootAbs: string;
  probeConfig?: ProbeDomainConfig;
  request: ExecutionOrchestrationRequest;
}): ReturnType<typeof executeExecutionOrchestrationAction> {
  switch (args.request.action) {
    case "execute":
      return executeExecutionOrchestrationAction({
        workspaceRootAbs: args.workspaceRootAbs,
        action: args.request.action,
        ...(args.probeConfig ? { probeConfig: args.probeConfig } : {}),
        payload: {
          projectName: args.request.input.projectName,
          executionProfile: args.request.input.executionProfile,
          ...(typeof args.request.input.suiteRunId === "string" ? { suiteRunId: args.request.input.suiteRunId } : {}),
          ...(typeof args.request.input.maxPlansPerCall === "number"
            ? { maxPlansPerCall: args.request.input.maxPlansPerCall }
            : {}),
        },
      });
  }
}
