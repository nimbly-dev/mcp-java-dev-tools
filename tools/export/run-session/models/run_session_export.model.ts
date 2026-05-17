import type {
  ExportRunSessionPs1Input,
  ExportRunSessionPs1Result,
  RunSessionExportManifest,
  RunSessionExportPlanRun,
} from "@tools-regression-execution-plan-spec/models/regression_run_session_export.model";

export type { ExportRunSessionPs1Input, ExportRunSessionPs1Result, RunSessionExportManifest, RunSessionExportPlanRun };

export type RuntimeStartup = {
  id: string;
  title: string;
  command: string;
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
};

export type Ps1TemplateModel = {
  manifest: RunSessionExportManifest;
  includeResolvedSecrets: boolean;
  runtimeStartupSection: string[];
  healthcheckGateSection: string[];
  planExecutionSection: string[];
};
