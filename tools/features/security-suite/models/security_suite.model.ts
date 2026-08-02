import type { RuntimeSuiteRunResult } from "@tools-execution-plan-spec/models/runtime_suite.model";
import type {
  SecurityMode,
  SecurityPlanContract,
} from "@tools-security-execution-plan-spec/models/security_contract.model";

export type SecurityMcpToolInvoker = (args: {
  toolName: string;
  input: Record<string, unknown>;
}) => Promise<{ structuredContent: Record<string, unknown> }>;

export type SecuritySuiteManifest = {
  executionProfile: string;
  suiteType: "security";
  executionPolicy: "stop_on_fail" | "continue_on_fail";
  plans: Array<{ order: number; planName: string; onFail?: "inherit" | "stop" | "continue" }>;
};

export type SecurityPlanArtifact = {
  metadata: Record<string, unknown>;
  contract: SecurityPlanContract;
  plan?: string;
};

export type ExecuteSecurityRuntimeSuiteArgs = {
  workspaceRootAbs: string;
  projectName: string;
  executionProfile: string;
  mcpInvoke: SecurityMcpToolInvoker;
  suiteRunId?: string;
  startPlanOrder?: number;
  priorPlanRuns?: RuntimeSuiteRunResult["planRuns"];
  maxPlansPerCall?: number;
};

export type SecurityModeExecutionResult = {
  status: "blocked";
  reasonCode: string;
  requiredUserAction: string[];
  reasonMeta?: { securityMode: SecurityMode; planName: string };
};
