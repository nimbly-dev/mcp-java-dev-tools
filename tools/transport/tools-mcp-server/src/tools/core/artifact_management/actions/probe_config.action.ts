import path from "node:path";
import { loadProbeRegistry } from "@/config/probe-registry";
import { buildFailClosedArtifactResponse, okArtifactResponse } from "@/tools/core/artifact_management/shared/fail_closed.util";
import { readJsonFile, writeJsonFile } from "@/tools/core/artifact_management/shared/json_io.util";
import type { ArtifactActionContext, ArtifactActionRequest, ArtifactActionResult } from "@/tools/core/artifact_management/actions/types";

export async function handleProbeConfigArtifact(
  ctx: ArtifactActionContext,
  request: ArtifactActionRequest<"probe_config">,
): Promise<ArtifactActionResult> {
  const probePath = path.join(ctx.workspaceRootAbs, ".mcpjvm", "probe-config.json");
  if (request.action === "read") {
    const artifact = await readJsonFile(probePath);
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      artifact,
    });
  }
  if (request.action === "validate") {
    const registry = loadProbeRegistry({ filePath: probePath, workspaceRootAbs: ctx.workspaceRootAbs });
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      defaultProbeId: registry.defaultProbeId,
      probeCount: registry.probesById.size,
    });
  }
  if (!request.input.payload) {
    return buildFailClosedArtifactResponse({
      reasonCode: "artifact_payload_required",
      reason: "payload is required for upsert",
      reasonMeta: { artifactType: request.artifactType, action: request.action },
    });
  }
  await writeJsonFile(probePath, request.input.payload);
  return okArtifactResponse({
    resultType: "artifact",
    status: "ok",
    artifactType: request.artifactType,
    action: request.action,
    path: probePath,
  });
}
