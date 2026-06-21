import {
  exportExecutionProfilePerformancePs1,
  exportExecutionProfilePerformanceSh,
  exportExecutionProfilePostman,
  exportExecutionProfilePs1,
  exportExecutionProfileSh,
} from "@tools-export-execution-profile/index";
import { loadExecutionProfileExportTarget } from "@tools-export-execution-profile/loaders/export_target.loader";
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

function parsePostmanReasonMeta(reason: string): Record<string, unknown> {
  if (reason.startsWith("postman_script_conversion_required:")) {
    const [, scriptName = "", extension = "unknown"] = reason.split(":");
    return { failedStep: "postman_script_validation", scriptName, extension };
  }
  if (reason.startsWith("postman_script_invalid_format:")) {
    const [, scriptName = "", detail = "invalid_js"] = reason.split(":");
    return { failedStep: "postman_script_validation", scriptName, detail };
  }
  if (reason.startsWith("postman_script_non_convertible:")) {
    const [, cause = "unknown", scriptName = ""] = reason.split(":");
    return { failedStep: "postman_script_resolution", cause, scriptName };
  }
  if (reason.startsWith("postman_provisioning_not_supported:")) {
    const [, scriptName = ""] = reason.split(":");
    return { failedStep: "postman_scope_guard", scriptName };
  }
  if (reason.startsWith("postman_export_blocked:")) {
    const parts = reason.split(":");
    const cause = parts[1] ?? "unknown";
    if (cause === "required_prerequisite_unresolved") {
      return { failedStep: "postman_export_render", cause, prerequisiteKey: parts[2] ?? "" };
    }
    if (cause === "binding_env_missing") {
      return { failedStep: "postman_export_render", cause, prerequisiteKey: parts[2] ?? "", envKey: parts[3] ?? "" };
    }
    if (cause === "provided_context_ambiguous") {
      return { failedStep: "postman_export_render", cause, contextKey: parts[2] ?? "" };
    }
    const planName = parts[2] ?? "";
    const stepId = parts[3] ?? "";
    return { failedStep: "postman_export_render", cause, ...(planName ? { planName } : {}), ...(stepId ? { stepId } : {}) };
  }
  return {};
}

export async function executionProfileExportDomain(input: {
  workspaceRootAbs: string;
  projectName?: string;
  exportId?: string;
  executionProfile?: string;
  planName?: string;
  when?: string;
  mode?: ExecutionProfileExportMode;
  type?: ExecutionProfileExportMode;
  includeResolvedSecrets?: boolean;
  includeRuntimeStartup?: boolean;
  includeHealthcheckGate?: boolean;
  contextBindings?: Record<string, string>;
  contextValues?: Record<string, string>;
}): Promise<{
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
}> {
  try {
    const selectedMode = input.mode ?? input.type;
    if (!selectedMode) {
      return blockedResponse(
        "execution_export_mode_required",
        "mode (or type alias) is required: choose exactly one of ps1|sh|postman",
        {
          ...(input.executionProfile ? { executionProfile: input.executionProfile } : {}),
          ...(input.planName ? { planName: input.planName } : {}),
          ...(input.projectName ? { projectName: input.projectName } : {}),
          neededInput: ["mode"],
          acceptedModes: ["ps1", "sh", "postman"],
          nextAction: "provide mode=ps1|sh|postman",
        },
      );
    }
    if (input.mode && input.type && input.mode !== input.type) {
      return blockedResponse(
        "execution_export_mode_conflict",
        "mode and type alias conflict; provide one value or matching values",
        {
          mode: input.mode,
          type: input.type,
          acceptedModes: ["ps1", "sh", "postman"],
        },
      );
    }

    const selectorInput: {
      workspaceRootAbs: string;
      projectName?: string;
      exportId?: string;
      executionProfile?: string;
      planName?: string;
      when?: string;
    } = {
      workspaceRootAbs: input.workspaceRootAbs,
      ...(typeof input.projectName === "string" && input.projectName.trim().length > 0
        ? { projectName: input.projectName.trim() }
        : {}),
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

    const selectedTarget = await loadExecutionProfileExportTarget(selectorInput);
    const resolvedExportId =
      selectedTarget.profile.suiteType === "performance"
        ? selectedTarget.exportId
        : await resolveExportIdForExport(selectorInput);

    const request: {
      workspaceRootAbs: string;
      projectName?: string;
      exportId: string;
      includeResolvedSecrets?: boolean;
      includeRuntimeStartup?: boolean;
      includeHealthcheckGate?: boolean;
      contextBindings?: Record<string, string>;
      contextValues?: Record<string, string>;
    } = {
      workspaceRootAbs: input.workspaceRootAbs,
      ...(typeof input.projectName === "string" && input.projectName.trim().length > 0
        ? { projectName: input.projectName.trim() }
        : {}),
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
    if (input.contextBindings && typeof input.contextBindings === "object") {
      request.contextBindings = input.contextBindings;
    }
    if (input.contextValues && typeof input.contextValues === "object") {
      request.contextValues = input.contextValues;
    }

    let out:
      | Awaited<ReturnType<typeof exportExecutionProfilePs1>>
      | Awaited<ReturnType<typeof exportExecutionProfileSh>>
      | Awaited<ReturnType<typeof exportExecutionProfilePostman>>
      | Awaited<ReturnType<typeof exportExecutionProfilePerformancePs1>>
      | Awaited<ReturnType<typeof exportExecutionProfilePerformanceSh>>;
    if (selectedTarget.profile.suiteType === "performance" && selectedMode === "postman") {
      return blockedResponse(
        "performance_export_mode_unsupported",
        "performance execution profile export supports ps1 and sh only; postman is not supported for workload replay",
        {
          suiteType: "performance",
          acceptedModes: ["ps1", "sh"],
          rejectedMode: selectedMode,
          ...(typeof input.projectName === "string" ? { projectName: input.projectName.trim() } : {}),
          executionProfile: selectedTarget.profile.executionProfile,
        },
      );
    }

    if (selectedMode === "ps1" && selectedTarget.profile.suiteType === "performance") {
      out = await exportExecutionProfilePerformancePs1(request);
    } else if (selectedMode === "sh" && selectedTarget.profile.suiteType === "performance") {
      out = await exportExecutionProfilePerformanceSh(request);
    } else if (selectedMode === "ps1") {
      out = await exportExecutionProfilePs1(request);
    } else if (selectedMode === "sh") {
      out = await exportExecutionProfileSh(request);
    } else {
      out = await exportExecutionProfilePostman(request);
    }

    let output: Record<string, unknown>;
    if ("scriptPathAbs" in out) {
      output = {
        scriptPathAbs: out.scriptPathAbs,
        ...("readmePathAbs" in out && out.readmePathAbs ? { readmePathAbs: out.readmePathAbs } : {}),
      };
    } else {
      output = {
        collectionPathAbs: out.collectionPathAbs,
        environmentPathAbs: out.environmentPathAbs,
      };
    }

    const structuredContent = {
      resultType: "execution_profile_export",
      status: "ok",
      mode: selectedMode,
      suiteType: selectedTarget.profile.suiteType,
      exportId: out.exportId,
      executionProfile: selectedTarget.profile.executionProfile,
      exportDirAbs: out.exportDirAbs,
      output,
    };

    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);
    let reasonCode = "execution_profile_export_failed";
    if (reason.startsWith("postman_script_conversion_required")) {
      reasonCode = "postman_script_conversion_required";
    } else if (reason.startsWith("postman_script_invalid_format")) {
      reasonCode = "postman_script_invalid_format";
    } else if (reason.startsWith("postman_provisioning_not_supported")) {
      reasonCode = "postman_provisioning_not_supported";
    } else if (reason.startsWith("postman_script_non_convertible")) {
      reasonCode = "postman_script_non_convertible";
    } else if (reason.startsWith("postman_export_blocked")) {
      reasonCode = "postman_export_blocked";
    } else if (
      reason === "export_selector_missing" ||
      reason === "execution_profile_export_manifest_missing" ||
      reason === "execution_profile_not_found" ||
      reason === "execution_profile_no_exports" ||
      reason === "export_selector_no_match" ||
      reason === "execution_profile_ambiguous" ||
      reason === "project_artifact_ambiguous" ||
      reason === "project_artifact_missing" ||
      reason === "export_id_invalid" ||
      reason === "performance_profile_required" ||
      reason === "performance_export_mode_unsupported"
    ) {
      reasonCode = reason;
    } else if (reason.startsWith("performance_export_workload_provider_unsupported:")) {
      reasonCode = "performance_export_workload_provider_unsupported";
    }
    return blockedResponse(reasonCode, reason, {
      ...(input.exportId ? { exportId: input.exportId } : {}),
      ...(input.executionProfile ? { executionProfile: input.executionProfile } : {}),
      ...(input.planName ? { planName: input.planName } : {}),
      ...(input.when ? { when: input.when } : {}),
      ...(input.mode ? { mode: input.mode } : {}),
      ...(input.type ? { type: input.type } : {}),
      ...parsePostmanReasonMeta(reason),
      ...(input.projectName ? { projectName: input.projectName } : {}),
    });
  }
}
