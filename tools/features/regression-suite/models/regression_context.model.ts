import type { ProjectRuntimeContext } from "@tools-project-artifact-spec/models/project_artifact.model";

export type ProjectContextBlockedReason =
  | "project_artifact_missing"
  | "project_artifact_invalid"
  | "project_reference_invalid"
  | "workspace_root_invalid"
  | "env_key_missing"
  | "script_execution_failed"
  | "runtime_context_unknown"
  | "external_system_invalid"
  | "external_healthcheck_failed";

export type ProjectContextResolutionResult =
  | { status: "ok"; contextPatch: Record<string, unknown>; secretContextKeys: string[]; runtimeContextName?: string }
  | {
      status: "blocked";
      reasonCode: ProjectContextBlockedReason;
      missing?: string[];
      checks?: string[];
      nextAction?: string;
      requiredUserAction: string[];
    };

export type ResolveProjectContextArgs = {
  workspaceRootAbs: string;
  projectsFileAbs: string;
  env?: Record<string, string | undefined>;
  runtimeContextName?: string;
  executionProfileName?: string;
  defaultsOverride?: { requestTimeoutMs?: number; retryMax?: number };
  healthChecksEnabled?: boolean;
  strictProbeVerification?: boolean;
  strictProbeBaseUrls?: string[];
  runtimeStarter?: RuntimeStarter;
};

export type RuntimeStartResult = { attempted: boolean; success: boolean; detail?: string };
export type RuntimeStarter = (args: { runtimeContext: ProjectRuntimeContext; workspaceRootAbs: string }) => Promise<RuntimeStartResult>;
