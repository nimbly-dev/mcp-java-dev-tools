import { ARTIFACT_ACTION_ALLOWLIST, type ArtifactType, type ArtifactManagementRequest } from "@tools-contracts/artifact-management";
import type { ArtifactActionContext, ArtifactActionResult } from "@tools-feature-artifact-management/actions/types";
import { handleProbeConfigArtifact } from "@tools-feature-artifact-management/actions/probe_config.action";
import { handleProjectContextArtifact } from "@tools-feature-artifact-management/actions/project_context.action";
import { handleRegressionPlanArtifact } from "@tools-feature-artifact-management/actions/regression_plan.action";
import { handleRunResultArtifact } from "@tools-feature-artifact-management/actions/run_result.action";
import { handleExecutionExportArtifact } from "@tools-feature-artifact-management/actions/execution_export.action";
import { buildFailClosedArtifactResponse } from "@tools-feature-artifact-management/shared/fail_closed.util";

function actionAllowed(artifactType: ArtifactType, action: string): boolean {
  const actions = ARTIFACT_ACTION_ALLOWLIST[artifactType];
  return (actions as readonly string[]).includes(action);
}

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
  if (request.artifactType === "probe_config") {
    return await handleProbeConfigArtifact(ctx, request);
  }
  if (request.artifactType === "project_context") {
    return await handleProjectContextArtifact(ctx, request);
  }
  if (request.artifactType === "regression_plan") {
    return await handleRegressionPlanArtifact(ctx, request);
  }
  if (request.artifactType === "run_result") {
    return await handleRunResultArtifact(ctx, request);
  }
  return await handleExecutionExportArtifact(ctx, request);
}

export async function artifactManagementDomain(input: {
  workspaceRootAbs: string;
  getProbeRegistrySummary?: ArtifactActionContext["getProbeRegistrySummary"];
  reloadProbeRegistry?: ArtifactActionContext["reloadProbeRegistry"];
  request: ArtifactManagementRequest;
}): Promise<ArtifactActionResult> {
  try {
    return await dispatchArtifactAction(
      {
        workspaceRootAbs: input.workspaceRootAbs,
        ...(input.getProbeRegistrySummary ? { getProbeRegistrySummary: input.getProbeRegistrySummary } : {}),
        ...(input.reloadProbeRegistry ? { reloadProbeRegistry: input.reloadProbeRegistry } : {}),
      },
      input.request,
    );
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);
    return buildFailClosedArtifactResponse({
      reasonCode: reason,
      reason,
      reasonMeta: {
        artifactType: input.request.artifactType,
        action: input.request.action,
      },
    });
  }
}
