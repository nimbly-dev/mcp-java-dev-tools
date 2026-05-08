export type ProjectRuntimeMode = "terminal" | "docker";

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
  externalSystems?: ProjectExternalSystem[];
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

