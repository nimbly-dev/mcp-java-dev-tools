import { promises as fs } from "node:fs";
import path from "node:path";

import { exportRunSessionPs1 } from "@tools-export-run-session/index";
import { resolveSessionIdForExport } from "@tools-export-run-session/loaders/session_selector.loader";
import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";

import { deriveNextActionCode, normalizeReasonMeta } from "@/utils/failure_diagnostics.util";

type RunSessionExportMode = "ps1" | "sh" | "postman";

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

async function tryReadLegacyPs1Export(input: {
  workspaceRootAbs: string;
  sessionId: string;
}): Promise<{ sessionDirAbs: string; scriptPathAbs: string; readmePathAbs?: string; metadataPathAbs?: string } | null> {
  const plansRootAbs = await resolveRegressionPlansRootAbs(input.workspaceRootAbs);
  const projectRootAbs = path.dirname(path.dirname(plansRootAbs));
  const sessionDirAbs = path.join(projectRootAbs, "exports", "session-runs-exports", input.sessionId);
  const scriptPathAbs = path.join(sessionDirAbs, "ps1", "run-session-export.ps1");
  try {
    await fs.access(scriptPathAbs);
  } catch {
    return null;
  }
  const readmePathAbs = path.join(sessionDirAbs, "ps1", "README.ps1.md");
  const metadataPathAbs = path.join(sessionDirAbs, "ps1", "export-metadata.json");
  return { sessionDirAbs, scriptPathAbs, readmePathAbs, metadataPathAbs };
}

export async function runSessionExportDomain(input: {
  workspaceRootAbs: string;
  sessionId?: string;
  executionProfile?: string;
  mode: RunSessionExportMode;
  includeResolvedSecrets?: boolean;
  includeRuntimeStartup?: boolean;
  includeHealthcheckGate?: boolean;
}): Promise<{
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
}> {
  if (input.mode !== "ps1") {
    return blockedResponse(
      "unsupported_mode",
      "Requested export mode is not implemented yet.",
      { mode: input.mode, supportedModes: ["ps1"] },
    );
  }

  try {
    const selectorInput: {
      workspaceRootAbs: string;
      sessionId?: string;
      executionProfile?: string;
    } = {
      workspaceRootAbs: input.workspaceRootAbs,
    };
    if (typeof input.sessionId === "string" && input.sessionId.trim().length > 0) {
      selectorInput.sessionId = input.sessionId;
    }
    if (typeof input.executionProfile === "string" && input.executionProfile.trim().length > 0) {
      selectorInput.executionProfile = input.executionProfile;
    }

    const resolvedSessionId = await resolveSessionIdForExport(selectorInput);

    const request: {
      workspaceRootAbs: string;
      sessionId: string;
      includeResolvedSecrets?: boolean;
      includeRuntimeStartup?: boolean;
      includeHealthcheckGate?: boolean;
    } = {
      workspaceRootAbs: input.workspaceRootAbs,
      sessionId: resolvedSessionId,
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

    let out;
    try {
      out = await exportRunSessionPs1(request);
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      if (reason === "session_manifest_missing") {
        const legacy = await tryReadLegacyPs1Export({
          workspaceRootAbs: input.workspaceRootAbs,
          sessionId: resolvedSessionId,
        });
        if (legacy) {
          const structuredContent = {
            resultType: "run_session_export",
            status: "ok",
            mode: "ps1",
            sessionId: resolvedSessionId,
            ...(input.executionProfile ? { executionProfile: input.executionProfile } : {}),
            sessionDirAbs: legacy.sessionDirAbs,
            output: {
              scriptPathAbs: legacy.scriptPathAbs,
              ...(legacy.readmePathAbs ? { readmePathAbs: legacy.readmePathAbs } : {}),
              ...(legacy.metadataPathAbs ? { metadataPathAbs: legacy.metadataPathAbs } : {}),
            },
            compatibility: "legacy_artifact_reused",
          };
          return {
            content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
            structuredContent,
          };
        }
      }
      throw error;
    }

    const structuredContent = {
      resultType: "run_session_export",
      status: "ok",
      mode: "ps1",
      sessionId: out.sessionId,
      ...(input.executionProfile ? { executionProfile: input.executionProfile } : {}),
      sessionDirAbs: out.sessionDirAbs,
      manifestPathAbs: out.manifestPathAbs,
      output: {
        scriptPathAbs: out.scriptPathAbs,
        readmePathAbs: out.readmePathAbs,
      },
    };

    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);
    const reasonCode =
      reason === "session_selector_missing" ||
      reason === "session_manifest_missing" ||
      reason === "execution_profile_not_found" ||
      reason === "execution_profile_no_sessions" ||
      reason === "session_id_invalid"
        ? reason
        : "run_session_export_failed";
    return blockedResponse(reasonCode, reason, {
      ...(input.sessionId ? { sessionId: input.sessionId } : {}),
      ...(input.executionProfile ? { executionProfile: input.executionProfile } : {}),
      mode: input.mode,
    });
  }
}
