import path from "node:path";
import { promises as fs } from "node:fs";
import type { ProjectArtifact } from "@tools-project-artifact-spec/models/project_artifact.model";
import {
  validateProjectArtifact,
  validateProjectArtifactReferenceIntegrity,
} from "@tools-project-artifact-spec/project_artifact.util";
import { readProjectArtifact, writeProjectArtifact } from "./project_artifact_io";
import { openRunStateStore } from "../state-store/run_state_store";
import { cutoverMarkerPath, cutoverSentinelPath } from "../state-store/state_store_cutover_marker";
import type {
  ArtifactActionContext,
  ArtifactActionRequest,
  ArtifactActionResult,
} from "../actions/types";
import { buildFailClosedArtifactResponse, okArtifactResponse } from "../shared/fail_closed";
import { fileExists, inspectProjectRoot } from "./project_context_root_inspection";
import {
  acquireProjectContextUpsertLock,
  releaseProjectContextUpsertLock,
} from "./project_context_upsert_lock";
import { mergeProjectArtifacts } from "./project_context_merge";
import { pickProjectContextQuery } from "./project_context_query";

function isEmptyOperationalStateStore(database: {
  prepare(sql: string): {
    get(...parameters: unknown[]): Record<string, unknown> | undefined;
    all(...parameters: unknown[]): Array<Record<string, unknown>>;
  };
}): boolean {
  try {
    const metadataTables = new Set([
      "schema_migrations",
      "schema_migration_resources",
      "store_metadata",
    ]);
    const tables = database
      .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
      .all()
      .map((row) => row.name)
      .filter((name): name is string => typeof name === "string" && !metadataTables.has(name));
    return tables.every((table) => {
      const identifier = table.replaceAll('"', '""');
      const row = database.prepare(`SELECT COUNT(*) AS count FROM "${identifier}"`).get();
      return row?.count === 0;
    });
  } catch {
    return false;
  }
}

async function validateProjectContext(
  request: ArtifactActionRequest<"project_context">,
  projectName: string,
  projectsFileAbs: string,
  projectRootAbs: string | undefined,
): Promise<ArtifactActionResult> {
  const validated = await readProjectArtifact(projectsFileAbs);
  if (!validated.ok) {
    return buildFailClosedArtifactResponse({
      reasonCode: validated.reasonCode,
      reason: validated.errors[0] ?? "project artifact invalid",
      reasonMeta: { errors: validated.errors, projectName },
    });
  }
  let resolvedRoot = projectRootAbs;
  if (!resolvedRoot) {
    const workspaceRoots = validated.artifact.workspaces.map((entry) =>
      path.resolve(entry.projectRoot),
    );
    if (workspaceRoots.length === 1) resolvedRoot = workspaceRoots[0];
  }
  if (resolvedRoot) {
    const matchedWorkspace = validated.artifact.workspaces.find(
      (entry) => path.resolve(entry.projectRoot) === resolvedRoot,
    );
    if (!matchedWorkspace) {
      return buildFailClosedArtifactResponse({
        reasonCode: "project_scope_mismatch",
        reason: "projectName and projectRootAbs do not resolve to the same project scope",
        reasonMeta: {
          failedStep: "project_scope_validation",
          projectName,
          projectRootAbs: resolvedRoot,
        },
      });
    }
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      projectRootAbs: resolvedRoot,
      workspaceCount: validated.artifact.workspaces.length,
      ...(await inspectProjectRoot(resolvedRoot)),
    });
  }
  return okArtifactResponse({
    resultType: "artifact",
    status: "ok",
    artifactType: request.artifactType,
    action: request.action,
    projectName,
    workspaceCount: validated.artifact.workspaces.length,
  });
}

async function readProjectContext(
  request: ArtifactActionRequest<"project_context">,
  projectName: string,
  projectsFileAbs: string,
): Promise<ArtifactActionResult> {
  const validated = await readProjectArtifact(projectsFileAbs);
  if (!validated.ok) {
    return buildFailClosedArtifactResponse({
      reasonCode: validated.reasonCode,
      reason: validated.errors[0] ?? "project artifact invalid",
      reasonMeta: { errors: validated.errors, projectName },
    });
  }
  const queryResult = pickProjectContextQuery({
    artifact: validated.artifact,
    ...(request.input.query ? { query: request.input.query } : {}),
  });
  return okArtifactResponse({
    resultType: "artifact",
    status: "ok",
    artifactType: request.artifactType,
    action: request.action,
    projectName,
    ...queryResult,
  });
}

async function upsertProjectContext(
  ctx: ArtifactActionContext,
  request: ArtifactActionRequest<"project_context">,
  projectName: string,
  projectsFileAbs: string,
): Promise<ArtifactActionResult> {
  if (!request.input.payload) {
    return buildFailClosedArtifactResponse({
      reasonCode: "artifact_payload_required",
      reason: "payload is required for upsert",
      reasonMeta: { artifactType: request.artifactType, action: request.action, projectName },
    });
  }
  const checked = validateProjectArtifact(request.input.payload);
  if (!checked.ok) {
    return buildFailClosedArtifactResponse({
      reasonCode: checked.reasonCode,
      reason: checked.errors[0] ?? "project artifact invalid",
      reasonMeta: { errors: checked.errors, projectName },
    });
  }
  const upsertLock = await acquireProjectContextUpsertLock(projectsFileAbs);
  if (!upsertLock) {
    return buildFailClosedArtifactResponse({
      reasonCode: "project_artifact_conflict",
      reason: "another project artifact upsert is active",
      reasonMeta: { projectName, failedStep: "project_artifact_upsert_lock" },
    });
  }
  let artifactToWrite: ProjectArtifact = checked.artifact;
  let updateMode: "created" | "merged" | "replaced" = "created";
  let stateStore:
    | {
        provisioned: true;
        databasePathRel: string;
        schemaVersion: number;
        cleanupEligible: boolean;
      }
    | undefined;
  try {
    const existingFile = await fileExists(projectsFileAbs);
    if (existingFile) {
      const existing = await readProjectArtifact(projectsFileAbs);
      if (!existing.ok && request.input.replace !== true) {
        return buildFailClosedArtifactResponse({
          reasonCode: existing.reasonCode,
          reason: existing.errors[0] ?? "existing project artifact is invalid",
          reasonMeta: {
            errors: existing.errors,
            projectName,
            failedStep: "existing_artifact_read",
          },
        });
      }
      if (request.input.replace === true) updateMode = "replaced";
      else if (existing.ok) {
        artifactToWrite = mergeProjectArtifacts(existing.artifact, checked.artifact);
        updateMode = "merged";
      }
    }
    const refsChecked = await validateProjectArtifactReferenceIntegrity({
      projectsFileAbs,
      artifact: artifactToWrite,
    });
    if (!refsChecked.ok) {
      return buildFailClosedArtifactResponse({
        reasonCode: refsChecked.reasonCode,
        reason: refsChecked.errors[0] ?? "project artifact invalid",
        reasonMeta: { errors: refsChecked.errors, projectName },
      });
    }
    if (!existingFile) {
      const databasePathAbs = path.join(
        ctx.workspaceRootAbs,
        ".mcpjvm",
        projectName,
        "run-state.sqlite",
      );
      const databaseExisted = await fileExists(databasePathAbs);
      const markerExisted =
        (await fileExists(cutoverMarkerPath(databasePathAbs))) ||
        (await fileExists(cutoverSentinelPath(ctx.workspaceRootAbs, projectName)));
      const opened = await openRunStateStore({
        workspaceRootAbs: ctx.workspaceRootAbs,
        projectName,
      });
      if (!opened.ok) {
        return buildFailClosedArtifactResponse({
          reasonCode: opened.reasonCode,
          reason: opened.reason,
          reasonMeta: {
            ...(opened.reasonMeta ?? {}),
            projectName,
            failedStep: "state_store_provision",
          },
        });
      }
      const cleanupEligible =
        !databaseExisted && !markerExisted && isEmptyOperationalStateStore(opened.database);
      opened.close();
      stateStore = {
        provisioned: true,
        databasePathRel: path
          .relative(ctx.workspaceRootAbs, opened.databasePathAbs)
          .replaceAll("\\", "/"),
        schemaVersion: opened.schemaVersion,
        cleanupEligible,
      };
    }
    try {
      await writeProjectArtifact(projectsFileAbs, artifactToWrite);
    } catch (error) {
      let cleanup: "removed" | "preserved" = "preserved";
      if (stateStore?.cleanupEligible) {
        const databasePathAbs = path.resolve(ctx.workspaceRootAbs, stateStore.databasePathRel);
        const markerPresent =
          (await fileExists(cutoverMarkerPath(databasePathAbs))) ||
          (await fileExists(cutoverSentinelPath(ctx.workspaceRootAbs, projectName)));
        if (!markerPresent && (await fileExists(databasePathAbs))) {
          const verification = await openRunStateStore({
            workspaceRootAbs: ctx.workspaceRootAbs,
            projectName,
          });
          if (verification.ok) {
            try {
              verification.database.exec("BEGIN EXCLUSIVE");
              const stillEmpty = isEmptyOperationalStateStore(verification.database);
              const markerAppeared =
                (await fileExists(cutoverMarkerPath(databasePathAbs))) ||
                (await fileExists(cutoverSentinelPath(ctx.workspaceRootAbs, projectName)));
              const walPathAbs = `${databasePathAbs}-wal`;
              const shmPathAbs = `${databasePathAbs}-shm`;
              if (
                stillEmpty &&
                !markerAppeared &&
                !(await fileExists(walPathAbs)) &&
                !(await fileExists(shmPathAbs))
              ) {
                const quarantinePathAbs = `${databasePathAbs}.provision-cleanup-${process.pid}-${Date.now()}`;
                try {
                  await fs.rename(databasePathAbs, quarantinePathAbs);
                  verification.close();
                  await fs.unlink(quarantinePathAbs);
                  cleanup = "removed";
                } catch {
                  await fs.unlink(quarantinePathAbs).catch(() => undefined);
                  verification.close();
                }
              } else verification.close();
            } catch {
              verification.close();
            }
          }
        }
      }
      return buildFailClosedArtifactResponse({
        reasonCode: "project_artifact_write_failed",
        reason: "project artifact could not be persisted after state-store provisioning",
        reasonMeta: {
          projectName,
          failedStep: "project_artifact_write",
          stateStoreCleanup: cleanup,
          error: error instanceof Error ? error.message : String(error),
        },
      });
    }
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      path: projectsFileAbs,
      updateMode,
      ...(stateStore
        ? {
            stateStore: {
              provisioned: stateStore.provisioned,
              databasePathRel: stateStore.databasePathRel,
              schemaVersion: stateStore.schemaVersion,
            },
          }
        : {}),
    });
  } finally {
    await releaseProjectContextUpsertLock(upsertLock);
  }
}

export function handleProjectContextLifecycle(
  ctx: ArtifactActionContext,
  request: ArtifactActionRequest<"project_context">,
  projectName: string,
  projectsFileAbs: string,
  projectRootAbs?: string,
): Promise<ArtifactActionResult> {
  if (request.action === "validate") {
    return validateProjectContext(request, projectName, projectsFileAbs, projectRootAbs);
  }
  if (request.action === "read") return readProjectContext(request, projectName, projectsFileAbs);
  return upsertProjectContext(ctx, request, projectName, projectsFileAbs);
}
