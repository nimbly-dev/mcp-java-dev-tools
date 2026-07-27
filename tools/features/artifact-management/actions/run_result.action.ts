import type { ArtifactActionContext, ArtifactActionRequest, ArtifactActionResult } from "./types";
import { buildFailClosedArtifactResponse } from "../shared/fail_closed";
import { resolveProjectName } from "../shared/project_resolution";
import { handleRunResultLifecycle } from "../support/run_result_lifecycle";

export async function handleRunResultArtifact(
  ctx: ArtifactActionContext,
  request: ArtifactActionRequest<"run_result">,
): Promise<ArtifactActionResult> {
  const requiredProjectActions: Record<string, { reasonCode: string; reason: string }> = {
    query: {
      reasonCode: "run_state_query_invalid",
      reason: "projectName is required for run_state queries",
    },
    rebuild: {
      reasonCode: "state_store_rebuild_source_invalid",
      reason: "projectName is required for state-store rebuild",
    },
    backfill: {
      reasonCode: "legacy_backfill_source_invalid",
      reason: "projectName is required for legacy correlation backfill",
    },
    cleanup: {
      reasonCode: "state_store_retention_invalid",
      reason: "projectName is required for retention cleanup",
    },
  };
  const requirement = requiredProjectActions[request.action];
  if (
    requirement &&
    (!request.input.projectName || request.input.projectName.trim().length === 0)
  ) {
    return buildFailClosedArtifactResponse({
      reasonCode: requirement.reasonCode,
      reason: requirement.reason,
      reasonMeta: { artifactType: request.artifactType, action: request.action },
    });
  }
  const projectName = await resolveProjectName(ctx.workspaceRootAbs, request.input.projectName);
  return handleRunResultLifecycle(ctx, request, projectName);
}
