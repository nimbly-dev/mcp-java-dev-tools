import { exportExecutionProfilePs1, exportExecutionProfileSh } from "@tools-export-execution-profile/index";
import { resolveExportIdForExport } from "@tools-export-execution-profile/loaders/export_selector.loader";

import { deriveNextActionCode, normalizeReasonMeta } from "@/utils/failure_diagnostics.util";

type ExecutionProfileExportMode = "ps1" | "sh" | "postman";

function blockedResponse(reasonCode: string, reason: string, reasonMeta?: Record<string, unknown>) {
  const structuredContent: Record<string, unknown> = {
    resultType: "report",
    status: reasonCode,
    reasonCode,
    nextActionCode: deriveNextActionCode(reasonCode),
    reason,
    ...(reasonMeta ? { reasonMeta: normalizeReasonMeta(reasonMeta) } : {}),
  };
  return {
    content: [{ type: "text" as const, text: JSON.stringify(structuredContent, null, 2) }],
    structuredContent,
  };
}

export async function executionProfileExportDomain(input: {
  workspaceRootAbs: string;
  exportId?: string;
  executionProfile?: string;
  planName?: string;
  when?: string;
  mode: ExecutionProfileExportMode;
  includeResolvedSecrets?: boolean;
  includeRuntimeStartup?: boolean;
  includeHealthcheckGate?: boolean;
}): Promise<{
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
}> {
  if (input.mode !== "ps1" && input.mode !== "sh") {
    return blockedResponse(
      "unsupported_mode",
      "Requested export mode is not implemented yet.",
      { mode: input.mode, supportedModes: ["ps1", "sh"] },
    );
  }

  try {
    const selectorInput: {
      workspaceRootAbs: string;
      exportId?: string;
      executionProfile?: string;
      planName?: string;
      when?: string;
    } = {
      workspaceRootAbs: input.workspaceRootAbs,
    };
    if (typeof input.exportId === "string" && input.exportId.trim().length > 0) {
      selectorInput.exportId = input.exportId;
    }
    if (typeof input.executionProfile === "string" && input.executionProfile.trim().length > 0) {
      selectorInput.executionProfile = input.executionProfile;
    }
    if (typeof input.planName === "string" && input.planName.trim().length > 0) {
      selectorInput.planName = input.planName;
    }
    if (typeof input.when === "string" && input.when.trim().length > 0) {
      selectorInput.when = input.when;
    }

    const resolvedExportId = await resolveExportIdForExport(selectorInput);

    const request: {
      workspaceRootAbs: string;
      exportId: string;
      includeResolvedSecrets?: boolean;
      includeRuntimeStartup?: boolean;
      includeHealthcheckGate?: boolean;
    } = {
      workspaceRootAbs: input.workspaceRootAbs,
      exportId: resolvedExportId,
    };
    if (typeof input.includeResolvedSecrets === "boolean") {
      request.includeResolvedSecrets = input.includeResolvedSecrets;
    }
    if (typeof input.includeRuntimeStartup === "boolean") {
      request.includeRuntimeStartup = input.includeRuntimeStartup;
    }
    if (typeof input.includeHealthcheckGate === "boolean") {
      request.includeHealthcheckGate = input.includeHealthcheckGate;
    }

    const out =
      input.mode === "ps1"
        ? await exportExecutionProfilePs1(request)
        : await exportExecutionProfileSh(request);

    const structuredContent = {
      resultType: "execution_profile_export",
      status: "ok",
      mode: input.mode,
      exportId: out.exportId,
      ...(input.executionProfile ? { executionProfile: input.executionProfile } : {}),
      exportDirAbs: out.exportDirAbs,
      output: {
        scriptPathAbs: out.scriptPathAbs,
        ...(out.readmePathAbs ? { readmePathAbs: out.readmePathAbs } : {}),
      },
    };

    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);
    const reasonCode =
      reason === "export_selector_missing" ||
      reason === "execution_profile_export_manifest_missing" ||
      reason === "execution_profile_not_found" ||
      reason === "execution_profile_no_exports" ||
      reason === "export_selector_no_match" ||
      reason === "export_id_invalid"
        ? reason
        : "execution_profile_export_failed";
    return blockedResponse(reasonCode, reason, {
      ...(input.exportId ? { exportId: input.exportId } : {}),
      ...(input.executionProfile ? { executionProfile: input.executionProfile } : {}),
      ...(input.planName ? { planName: input.planName } : {}),
      ...(input.when ? { when: input.when } : {}),
      mode: input.mode,
    });
  }
}
