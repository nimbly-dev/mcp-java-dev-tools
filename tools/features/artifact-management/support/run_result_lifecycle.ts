import path from "node:path";
import { promises as fs } from "node:fs";
import type {
  ArtifactActionContext,
  ArtifactActionRequest,
  ArtifactActionResult,
} from "../actions/types";
import { buildFailClosedArtifactResponse, okArtifactResponse } from "../shared/fail_closed";
import { cleanupRunStateRetention } from "../state-store/run_state_retention_cleanup";
import { backfillLegacyCorrelationIndex } from "../state-store/legacy_backfill_state_store";
import { cutoverRunStateStore } from "../state-store/state_store_cutover";
import { rebuildRunStateStore } from "../state-store/rebuild/run_state_store_rebuild";
import type { RunStateRebuildResult } from "../state-store/model/run_state_store.model";
import { handleRunResultQuery } from "./run_result_query";
import { readRunResultArtifact, workspaceRelativePath } from "./run_result_artifact";

export async function handleRunResultLifecycle(
  ctx: ArtifactActionContext,
  request: ArtifactActionRequest<"run_result">,
  projectName: string,
): Promise<ArtifactActionResult> {
  if (request.action === "cleanup") {
    const cleanup = await cleanupRunStateRetention({
      workspaceRootAbs: ctx.workspaceRootAbs,
      projectName,
      ...(request.input.retention ? { retention: request.input.retention } : {}),
    });
    if (!cleanup.ok) {
      return buildFailClosedArtifactResponse({
        reasonCode: cleanup.reasonCode,
        reason: cleanup.reason,
        ...(cleanup.reasonMeta ? { reasonMeta: cleanup.reasonMeta } : {}),
      });
    }
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName: cleanup.projectName,
      cleanupId: cleanup.cleanupId,
      summary: cleanup.summary,
    });
  }

  if (request.action === "query") return handleRunResultQuery(ctx, request, projectName);

  if (request.action === "backfill") {
    if (request.input.stateSurface !== "correlation_state") {
      return buildFailClosedArtifactResponse({
        reasonCode: "legacy_backfill_source_invalid",
        reason: "stateSurface must be 'correlation_state' for run_result backfill",
        reasonMeta: { artifactType: request.artifactType, action: request.action },
      });
    }
    const backfill = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: ctx.workspaceRootAbs,
      projectName,
    });
    if (!backfill.ok) {
      return buildFailClosedArtifactResponse({
        reasonCode: backfill.reasonCode,
        reason: backfill.reason,
        ...(backfill.reasonMeta ? { reasonMeta: backfill.reasonMeta } : {}),
      });
    }
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      transitional: {
        kind: "legacy_correlation_index_backfill",
        preCutoverOnly: true,
        sourcePathRel: backfill.summary.sourcePathRel,
      },
      summary: backfill.summary,
    });
  }

  if (request.action === "cutover") {
    const cutover = await cutoverRunStateStore({
      workspaceRootAbs: ctx.workspaceRootAbs,
      projectName,
    });
    if (!cutover.ok) {
      return buildFailClosedArtifactResponse({
        reasonCode: cutover.reasonCode,
        reason: cutover.reason,
        ...(cutover.reasonMeta ? { reasonMeta: cutover.reasonMeta } : {}),
      });
    }
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      summary: cutover.cutover,
      ...(cutover.idempotent ? { idempotent: true } : {}),
    });
  }

  if (request.action === "rebuild") {
    const strict = request.input.strict === true;
    const rebuilt = (await rebuildRunStateStore({
      workspaceRootAbs: ctx.workspaceRootAbs,
      projectName,
      strict,
    })) as RunStateRebuildResult;
    if (!rebuilt.ok) {
      return buildFailClosedArtifactResponse({
        reasonCode: rebuilt.reasonCode,
        reason: rebuilt.reason,
      });
    }
    const databasePathRel = workspaceRelativePath(ctx.workspaceRootAbs, rebuilt.databasePathAbs);
    const quarantinePathRel = rebuilt.quarantinePathAbs
      ? workspaceRelativePath(ctx.workspaceRootAbs, rebuilt.quarantinePathAbs)
      : undefined;
    if (!databasePathRel || (rebuilt.quarantinePathAbs && !quarantinePathRel)) {
      return buildFailClosedArtifactResponse({
        reasonCode: "state_store_rebuild_replace_failed",
        reason: "rebuild result path is outside the workspace",
        reasonMeta: { projectName },
      });
    }
    const summary = rebuilt.summary;
    const partial =
      summary.invalidRuns > 0 ||
      summary.skippedRuns > 0 ||
      summary.conflictingRuns > 0 ||
      summary.nonReconstructibleActiveStates > 0;
    return okArtifactResponse({
      resultType: "artifact",
      status: partial ? "degraded" : "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      scope: {
        stateSurfaces: request.input.scope?.stateSurfaces ?? [
          "run_state",
          "correlation_state",
          "watcher_state",
        ],
      },
      strict,
      databasePathRel,
      ...(quarantinePathRel ? { quarantinePathRel } : {}),
      summary: { ...summary, recoveryStatus: partial ? "partial" : "complete" },
    });
  }

  if (request.action === "list") {
    const planName = request.input.planName?.trim();
    if (!planName) {
      return buildFailClosedArtifactResponse({
        reasonCode: "plan_name_required",
        reason: "planName is required for run_result list",
        reasonMeta: { action: request.action },
      });
    }
    const runsRoot = path.join(
      ctx.workspaceRootAbs,
      ".mcpjvm",
      projectName,
      "plans",
      "regression",
      planName,
      "runs",
    );
    const runs = await fs.readdir(runsRoot, { withFileTypes: true }).catch(() => []);
    const runIds = runs
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
      .sort((a, b) => b.localeCompare(a));
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      planName,
      runIds,
    });
  }

  return readRunResultArtifact(request, ctx.workspaceRootAbs, projectName);
}
