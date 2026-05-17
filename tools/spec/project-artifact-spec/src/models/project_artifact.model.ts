export type ProjectRuntimeMode = "terminal" | "docker";
export type RunPrerequisiteType = "assert" | "script";
export type RunPrerequisiteOnFail = "block" | "skip_remaining";
export type RunPrerequisiteAssertKind =
  | "env_exists"
  | "context_exists"
  | "file_exists"
  | "port_reachable"
  | "url_reachable"
  | "command_available";
export type RunPrerequisiteScriptCommand = "python" | "node" | "sh" | "ps";

export type RunPrerequisiteAssert = {
  kind: RunPrerequisiteAssertKind;
  key?: string;
  path?: string;
  host?: string;
  port?: number;
  url?: string;
  name?: string;
  timeoutMs?: number;
};

export type RunPrerequisiteScript = {
  command: RunPrerequisiteScriptCommand;
  scriptPath: string;
  args?: string[];
  cwd?: string;
  timeoutMs?: number;
};

export type RunPrerequisite = {
  order: number;
  id: string;
  type: RunPrerequisiteType;
  onFail: RunPrerequisiteOnFail;
  assert?: RunPrerequisiteAssert;
  script?: RunPrerequisiteScript;
};

export type ExecutionProfilePolicy = "stop_on_fail" | "continue_on_fail";
export type ExecutionProfilePlanOnFail = "inherit" | "stop" | "continue";
export type ExecutionProfileRuntimeConfig = {
  requestTimeoutMs?: number;
  retryMax?: number;
};
export type ExecutionProfilePlanEntry = {
  order: number;
  planName: string;
  onFail?: ExecutionProfilePlanOnFail;
  runtimeContextName?: string;
  providedContext?: Record<string, unknown>;
};
export type ExecutionProfileEntry = {
  executionProfile: string;
  runtimeContextName?: string;
  executionPolicy: ExecutionProfilePolicy;
  runtimeConfig?: ExecutionProfileRuntimeConfig;
  plans: ExecutionProfilePlanEntry[];
};

export type ProjectRuntimeStartupEntry = {
  name: string;
  command: string;
  args?: string[];
  appdir?: string;
  env?: Record<string, string>;
};

export type ProjectRuntimeContext = {
  name: string;
  mode: ProjectRuntimeMode;
  composeFile?: string;
  autoStart?: boolean;
  autoStopOnFinish?: boolean;
  startups?: ProjectRuntimeStartupEntry[];
};

export type ExternalHealthCheck =
  | {
      id: string;
      type: "tcp";
      target: string;
      timeoutMs?: number;
      required?: boolean;
    }
  | {
      id: string;
      type: "http";
      method?: "GET" | "POST" | "PUT" | "DELETE" | "PATCH" | "HEAD" | "OPTIONS";
      url: string;
      expect?: {
        status?: number;
      };
      timeoutMs?: number;
      required?: boolean;
    };

export type ProjectExternalSystem = {
  name: string;
  kind: string;
  host: string;
  port: number;
  healthChecks?: ExternalHealthCheck[];
};

export type ProjectWorkspaceEntry = {
  projectRoot: string;
  envFile?: string;
  variables?: {
    bearerTokenEnv?: string;
  };
  runtimeContexts?: ProjectRuntimeContext[];
  executionProfiles?: ExecutionProfileEntry[];
  runPrerequisites?: RunPrerequisite[];
  externalSystems?: ProjectExternalSystem[];
  sessionExport?: {
    includeRuntimeStartup?: boolean;
    includeHealthcheckGate?: boolean;
  };
  defaults?: {
    requestTimeoutMs?: number;
    retryMax?: number;
  };
};

export type ProjectArtifact = {
  workspaces: ProjectWorkspaceEntry[];
};

export type ProjectArtifactValidationResult =
  | {
      ok: true;
      artifact: ProjectArtifact;
    }
  | {
      ok: false;
      reasonCode:
        | "project_artifact_invalid"
        | "workspace_root_invalid"
        | "env_key_missing"
        | "runtime_context_unknown"
        | "external_system_invalid";
      errors: string[];
    };

