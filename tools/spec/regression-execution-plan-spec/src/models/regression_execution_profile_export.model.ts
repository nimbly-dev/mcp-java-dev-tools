export type * from "@tools-execution-plan-spec/models/execution_profile_export.model";

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
