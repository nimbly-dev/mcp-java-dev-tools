import { promises as fs } from "node:fs";
import path from "node:path";
import { executionProfileExportDomain } from "@/tools/core/execution_profile_export/domain";
import type { ArtifactActionContext, ArtifactActionRequest, ArtifactActionResult } from "@/tools/core/artifact_management/actions/types";
import { buildFailClosedArtifactResponse, okArtifactResponse } from "@/tools/core/artifact_management/shared/fail_closed.util";
import { resolveProjectName } from "@/tools/core/artifact_management/shared/project_resolution.util";

export async function handleExecutionExportArtifact(
  ctx: ArtifactActionContext,
  request: ArtifactActionRequest<"execution_export">,
): Promise<ArtifactActionResult> {
  if (request.action === "generate") {
    if (!request.input.mode) {
      return buildFailClosedArtifactResponse({
        reasonCode: "execution_export_mode_required",
        reason: "mode is required for execution_export generate",
      });
    }
    return await executionProfileExportDomain({
      workspaceRootAbs: ctx.workspaceRootAbs,
      mode: request.input.mode,
      ...(request.input.executionProfile ? { executionProfile: request.input.executionProfile } : {}),
      ...(request.input.planName ? { planName: request.input.planName } : {}),
      ...(request.input.when ? { when: request.input.when } : {}),
      ...(typeof request.input.includeResolvedSecrets === "boolean"
        ? { includeResolvedSecrets: request.input.includeResolvedSecrets }
        : {}),
      ...(typeof request.input.includeRuntimeStartup === "boolean"
        ? { includeRuntimeStartup: request.input.includeRuntimeStartup }
        : {}),
      ...(typeof request.input.includeHealthcheckGate === "boolean"
        ? { includeHealthcheckGate: request.input.includeHealthcheckGate }
        : {}),
      ...(request.input.contextBindings ? { contextBindings: request.input.contextBindings } : {}),
      ...(request.input.contextValues ? { contextValues: request.input.contextValues } : {}),
    });
  }

  const projectName = await resolveProjectName(ctx.workspaceRootAbs, request.input.projectName);
  const exportsRoot = path.join(ctx.workspaceRootAbs, ".mcpjvm", projectName, "exports");
  if (request.action === "list") {
    const entries = await fs.readdir(exportsRoot, { withFileTypes: true }).catch(() => []);
    const exportFolders = entries
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
      .sort((a, b) => b.localeCompare(a));
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      exportFolders,
    });
  }

  const exportId = request.input.query?.exportId;
  if (!exportId) {
    return buildFailClosedArtifactResponse({
      reasonCode: "export_id_required",
      reason: "input.query.exportId is required for execution_export read",
      reasonMeta: { action: request.action },
    });
  }
  const exportDir = path.join(exportsRoot, exportId);
  const entries = await fs.readdir(exportDir, { withFileTypes: true });
  const files = entries
    .filter((entry) => entry.isFile())
    .map((entry) => entry.name)
    .sort((a, b) => a.localeCompare(b));
  return okArtifactResponse({
    resultType: "artifact",
    status: "ok",
    artifactType: request.artifactType,
    action: request.action,
    projectName,
    exportId,
    files,
  });
}
