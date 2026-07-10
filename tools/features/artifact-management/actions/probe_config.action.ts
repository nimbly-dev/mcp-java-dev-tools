import path from "node:path";
import * as fs from "node:fs";
import { loadProbeRegistry, summarizeProbeRegistry } from "@tools-core/probe-registry";
import { buildFailClosedArtifactResponse, okArtifactResponse } from "../shared/fail_closed";
import { readJsonFile, writeJsonFile } from "../shared/json_io";
import type { ArtifactActionContext, ArtifactActionRequest, ArtifactActionResult } from "./types";

function buildProbeConfigNotConfiguredResponse(
  request: ArtifactActionRequest<"probe_config">,
): ArtifactActionResult {
  return okArtifactResponse({
    resultType: "artifact",
    status: "not_configured",
    artifactType: request.artifactType,
    action: request.action,
    reasonCode: "probe_registry_not_configured",
    nextActionCode: "set_probe_registry_config",
    nextAction:
      "Place .mcpjvm/probe-config.json under the workspace (or a parent directory), then restart MCP server.",
  });
}

function loadProbeConfigSummary(ctx: ArtifactActionContext, probePath: string) {
  const activeSummary = ctx.getProbeRegistrySummary?.();
  if (activeSummary) return activeSummary;
  if (!fs.existsSync(probePath)) return undefined;
  return summarizeProbeRegistry(
    loadProbeRegistry({
      filePath: probePath,
      workspaceRootAbs: ctx.workspaceRootAbs,
    }),
  );
}

export async function handleProbeConfigArtifact(
  ctx: ArtifactActionContext,
  request: ArtifactActionRequest<"probe_config">,
): Promise<ArtifactActionResult> {
  const probePath = path.join(ctx.workspaceRootAbs, ".mcpjvm", "probe-config.json");
  if (request.action === "read") {
    const summary = loadProbeConfigSummary(ctx, probePath);
    if (!summary) {
      return buildProbeConfigNotConfiguredResponse(request);
    }
    let artifact: unknown;
    try {
      artifact = await readJsonFile(probePath);
    } catch {
      // Preserve active registry summary even when the watched file is currently invalid on disk.
    }
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      ...summary,
      ...(artifact ? { artifact } : {}),
    });
  }
  if (request.action === "validate") {
    if (!fs.existsSync(probePath)) {
      return buildProbeConfigNotConfiguredResponse(request);
    }
    const registry = loadProbeRegistry({ filePath: probePath, workspaceRootAbs: ctx.workspaceRootAbs });
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      ...summarizeProbeRegistry(registry),
    });
  }
  if (request.action === "reload") {
    const summary =
      ctx.reloadProbeRegistry?.() ??
      (fs.existsSync(probePath)
        ? summarizeProbeRegistry(
            loadProbeRegistry({
              filePath: probePath,
              workspaceRootAbs: ctx.workspaceRootAbs,
            }),
          )
        : undefined);
    if (!summary) {
      return buildProbeConfigNotConfiguredResponse(request);
    }
    return okArtifactResponse({
      resultType: "artifact",
      status: "reloaded",
      artifactType: request.artifactType,
      action: request.action,
      ...summary,
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
  const summary =
    ctx.reloadProbeRegistry?.() ??
    summarizeProbeRegistry(
      loadProbeRegistry({
        filePath: probePath,
        workspaceRootAbs: ctx.workspaceRootAbs,
      }),
    );
  return okArtifactResponse({
    resultType: "artifact",
    status: "ok",
    artifactType: request.artifactType,
    action: request.action,
    path: probePath,
    ...summary,
  });
}
