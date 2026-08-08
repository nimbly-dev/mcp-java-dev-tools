import type {
  ExecutionProfileExportManifest,
  ExecutionProfileExportPlanRun,
  ExecutionProfileSuiteType,
} from "@tools-execution-plan-spec/models/execution_profile_export.model";

export type {
  ExecutionProfileExportManifest,
  ExecutionProfileExportPlanRun,
  ExecutionProfileSuiteType,
};

export type ExportExecutionProfilePs1Input = {
  workspaceRootAbs: string;
  projectName?: string;
  exportId: string;
  includeResolvedSecrets?: boolean;
  includeRuntimeStartup?: boolean;
  includeHealthcheckGate?: boolean;
  contextBindings?: Record<string, string>;
  contextValues?: Record<string, string>;
};

export type ExportExecutionProfilePs1Result = {
  exportId: string;
  exportDirAbs: string;
  scriptPathAbs: string;
  readmePathAbs?: string;
  jmeterArtifactPathsAbs?: string[];
};

export type RuntimeStartup = {
  id: string;
  title: string;
  command: string;
  background?: boolean;
  autoStopOnFinish?: boolean;
  teardownCommand?: string;
};

export type Healthcheck = {
  id: string;
  title: string;
  required: boolean;
  type: "tcp" | "http";
  target?: string;
  url?: string;
};

export type HealthcheckCommand = {
  id: string;
  title: string;
  command: string;
};

export type ExportRuntimeDefaults = {
  includeRuntimeStartup: boolean;
  includeHealthcheckGate: boolean;
  includeResolvedSecrets: boolean;
};

export type Ps1TemplateModel = {
  manifest: ExecutionProfileExportManifest;
  includeResolvedSecrets: boolean;
  runtimeStartupSection: string[];
  healthcheckGateSection: string[];
  planExecutionSection: string[];
};
