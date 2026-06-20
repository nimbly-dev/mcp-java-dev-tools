import type {
  ExportExecutionProfilePs1Input,
  ExportExecutionProfilePs1Result,
  ExecutionProfileExportManifest,
  ExecutionProfileExportPlanRun,
  ExecutionProfileSuiteType,
} from "@tools-regression-execution-plan-spec/models/regression_execution_profile_export.model";

export type {
  ExportExecutionProfilePs1Input,
  ExportExecutionProfilePs1Result,
  ExecutionProfileExportManifest,
  ExecutionProfileExportPlanRun,
  ExecutionProfileSuiteType,
};

export type RuntimeStartup = {
  id: string;
  title: string;
  command: string;
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
