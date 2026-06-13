import { isRecord } from "@tools-export-execution-profile/common";
import type { ExportExecutionProfilePs1Input, ExportRuntimeDefaults } from "@tools-export-execution-profile/models/execution_profile_export.model";

export function resolveExportDefaults(input: {
  request: ExportExecutionProfilePs1Input;
  workspace: Record<string, unknown> | undefined;
}): ExportRuntimeDefaults {
  let defaults: Record<string, unknown> | undefined;

  if (input.workspace && isRecord(input.workspace.sessionExport)) {
    defaults = input.workspace.sessionExport;
  }

  let includeRuntimeStartup = true;
  if (typeof defaults?.includeRuntimeStartup === "boolean") {
    includeRuntimeStartup = defaults.includeRuntimeStartup;
  }
  if (typeof input.request.includeRuntimeStartup === "boolean") {
    includeRuntimeStartup = input.request.includeRuntimeStartup;
  }

  let includeHealthcheckGate = true;
  if (typeof defaults?.includeHealthcheckGate === "boolean") {
    includeHealthcheckGate = defaults.includeHealthcheckGate;
  }
  if (typeof input.request.includeHealthcheckGate === "boolean") {
    includeHealthcheckGate = input.request.includeHealthcheckGate;
  }

  // Secret export always requires explicit request opt-in.
  const includeResolvedSecrets = input.request.includeResolvedSecrets === true;

  return {
    includeRuntimeStartup,
    includeHealthcheckGate,
    includeResolvedSecrets,
  };
}
