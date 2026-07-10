import type { ArtifactManagementRequest } from "@tools-contracts/artifact-management";
import type { ArtifactActionContext, ArtifactActionResult } from "./actions/types";
import { dispatchArtifactAction } from "./actions";
import { buildFailClosedArtifactResponse } from "./shared/fail_closed";

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
