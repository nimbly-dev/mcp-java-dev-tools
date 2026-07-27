import path from "node:path";
import type { ArtifactActionContext, ArtifactActionRequest, ArtifactActionResult } from "./types";
import { buildFailClosedArtifactResponse, okArtifactResponse } from "../shared/fail_closed";
import { listProjectNames } from "../shared/project_resolution";
import { resolveProjectContextTarget } from "../support/project_context_target";
import { handleProjectContextLifecycle } from "../support/project_context_lifecycle";

export async function handleProjectContextArtifact(
  ctx: ArtifactActionContext,
  request: ArtifactActionRequest<"project_context">,
): Promise<ArtifactActionResult> {
  if (request.action === "list") {
    const projectNames = await listProjectNames(ctx.workspaceRootAbs);
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectNames,
    });
  }

  const projectTarget = await resolveProjectContextTarget({
    workspaceRootAbs: ctx.workspaceRootAbs,
    ...(typeof request.input.projectName === "string"
      ? { projectName: request.input.projectName }
      : {}),
    ...(typeof request.input.projectRootAbs === "string"
      ? { projectRootAbs: request.input.projectRootAbs }
      : {}),
  });
  if (!projectTarget.ok) {
    return buildFailClosedArtifactResponse({
      reasonCode: projectTarget.reasonCode,
      reason: projectTarget.reason,
      ...(projectTarget.reasonMeta ? { reasonMeta: projectTarget.reasonMeta } : {}),
    });
  }

  const projectsFileAbs = path.join(
    ctx.workspaceRootAbs,
    ".mcpjvm",
    projectTarget.projectName,
    "projects.json",
  );
  return handleProjectContextLifecycle(
    ctx,
    request,
    projectTarget.projectName,
    projectsFileAbs,
    projectTarget.projectRootAbs,
  );
}
