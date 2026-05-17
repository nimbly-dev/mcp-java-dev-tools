import { isRecord } from "@tools-export-run-session/common";
import type { ExportRunSessionPs1Input, ExportRuntimeDefaults } from "@tools-export-run-session/models/run_session_export.model";

export function resolveExportDefaults(input: {
  request: ExportRunSessionPs1Input;
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

  return {
    includeRuntimeStartup,
    includeHealthcheckGate,
  };
}
