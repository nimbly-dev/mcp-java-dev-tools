import { ARTIFACT_ACTION_ALLOWLIST, type ArtifactManagementRequest, type ArtifactType } from "@tools-contracts/artifact-management";
import { buildFailClosedArtifactResponse } from "../shared/fail_closed";
import type { ArtifactActionContext, ArtifactActionResult } from "./types";
import { handleExecutionExportArtifact } from "./execution_export.action";
import { handleProbeConfigArtifact } from "./probe_config.action";
import { handleProjectContextArtifact } from "./project_context.action";
import { handleRegressionPlanArtifact } from "./regression_plan.action";
import { handleRunResultArtifact } from "./run_result.action";

function actionAllowed(artifactType: ArtifactType, action: string): boolean {
  return (ARTIFACT_ACTION_ALLOWLIST[artifactType] as readonly string[]).includes(action);
}

/** Typed Artifact Management action dispatch. Product behavior remains in named actions. */
export async function dispatchArtifactAction(
  ctx: ArtifactActionContext,
  request: ArtifactManagementRequest,
): Promise<ArtifactActionResult> {
  if (!actionAllowed(request.artifactType, request.action)) {
    return buildFailClosedArtifactResponse({
      reasonCode: "artifact_action_not_allowed",
      reason: `action '${request.action}' is not permitted for artifactType '${request.artifactType}'`,
      reasonMeta: {
        artifactType: request.artifactType,
        action: request.action,
        allowedActions: [...ARTIFACT_ACTION_ALLOWLIST[request.artifactType]],
      },
    });
  }
  switch (request.artifactType) {
    case "probe_config": return handleProbeConfigArtifact(ctx, request);
    case "project_context": return handleProjectContextArtifact(ctx, request);
    case "regression_plan": return handleRegressionPlanArtifact(ctx, request);
    case "run_result": return handleRunResultArtifact(ctx, request);
    case "execution_export": return handleExecutionExportArtifact(ctx, request);
  }
}
