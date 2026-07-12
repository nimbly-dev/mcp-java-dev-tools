import { promises as fs } from "node:fs";
import path from "node:path";
import { applyRunStateStoreMigrations } from "./run_state_store_schema";
import { cutoverMarkerPath, cutoverSentinelPath } from "./state_store_cutover_marker";
import type {
  RunStateDatabase,
  RunStateStoreFailure,
  RunStateStoreFailureCode,
  RunStateStoreOpenResult,
} from "./model/run_state_store.model";

const { DatabaseSync } = require("node:sqlite") as {
  DatabaseSync: new (location: string) => RunStateDatabase;
};

function failure(
  reasonCode: RunStateStoreFailureCode,
  reason: string,
  nextAction: RunStateStoreFailure["nextAction"],
  reasonMeta?: Record<string, unknown>,
): RunStateStoreFailure {
  return { ok: false, reasonCode, reason, nextAction, ...(reasonMeta ? { reasonMeta } : {}) };
}

function normalizeProjectName(projectName: string): string | RunStateStoreFailure {
  const normalized = projectName.trim();
  if (!normalized || normalized === "." || normalized === ".." || /[\\/]/.test(normalized))
    return failure(
      "state_store_path_invalid",
      "projectName must be a non-empty path segment",
      "correct_state_store_input",
    );
  return normalized;
}

function classifyDatabaseError(
  error: unknown,
  databasePathAbs: string,
  fallback: RunStateStoreFailureCode,
): RunStateStoreFailure {
  const message = (error instanceof Error ? error.message : String(error)).toLowerCase();
  if (message.includes("database is locked") || message.includes("busy"))
    return failure("state_store_locked", "run-state SQLite store is locked", "retry_state_store", {
      databasePathAbs,
    });
  if (
    message.includes("not a database") ||
    message.includes("malformed") ||
    message.includes("corrupt")
  )
    return failure(
      "state_store_corrupt",
      "run-state SQLite store could not be opened and was preserved for recovery",
      "rebuild_state_store",
      { databasePathAbs },
    );
  return failure(
    fallback,
    "run-state SQLite store operation failed",
    fallback === "state_store_migration_failed" ? "rebuild_state_store" : "retry_state_store",
    { databasePathAbs },
  );
}

/** Opens the per-project operational state store. Callers must always invoke close(). */
export async function openRunStateStore(args: {
  workspaceRootAbs: string;
  projectName: string;
}): Promise<RunStateStoreOpenResult> {
  const projectName = normalizeProjectName(args.projectName);
  if (typeof projectName !== "string") return projectName;
  const rootAbs = path.resolve(args.workspaceRootAbs, ".mcpjvm");
  const projectDirAbs = path.resolve(rootAbs, projectName);
  if (!projectDirAbs.startsWith(`${rootAbs}${path.sep}`))
    return failure(
      "state_store_path_invalid",
      "projectName resolves outside the .mcpjvm directory",
      "correct_state_store_input",
    );
  try {
    await fs.mkdir(projectDirAbs, { recursive: true });
  } catch (error) {
    return failure(
      "state_store_open_failed",
      "run-state SQLite directory could not be created",
      "retry_state_store",
      { projectDirAbs, error: error instanceof Error ? error.message : String(error) },
    );
  }
  const databasePathAbs = path.join(projectDirAbs, "run-state.sqlite");
  const cutoverMarkerAbs = cutoverMarkerPath(databasePathAbs);
  const cutoverSentinelAbs = cutoverSentinelPath(args.workspaceRootAbs, projectName);
  const cutoverMarkerExists = await fs
    .stat(cutoverMarkerAbs)
    .then(() => true)
    .catch(() => false);
  const cutoverSentinelExists = await fs
    .stat(cutoverSentinelAbs)
    .then(() => true)
    .catch(() => false);
  const databaseExists = await fs
    .stat(databasePathAbs)
    .then(() => true)
    .catch(() => false);
  if ((cutoverMarkerExists || cutoverSentinelExists) && !databaseExists)
    return failure(
      "state_store_required_after_cutover",
      "the SQLite state store is required after cutover and cannot be recreated implicitly",
      "repair_state_store",
      { databasePathAbs, cutoverMarkerAbs, cutoverSentinelAbs },
    );
  let database: RunStateDatabase;
  try {
    database = new DatabaseSync(databasePathAbs);
  } catch (error) {
    return classifyDatabaseError(error, databasePathAbs, "state_store_open_failed");
  }
  const migration = applyRunStateStoreMigrations(database, projectName, databasePathAbs);
  if (!migration.ok) {
    database.close();
    return migration;
  }
  if (cutoverMarkerExists || cutoverSentinelExists) {
    const cutover = database
      .prepare("SELECT status FROM state_store_cutover WHERE project_name = ?")
      .get(projectName);
    if (cutover?.status !== "cutover_complete") {
      database.close();
      return failure(
        "state_store_cutover_failed",
        "the SQLite state store does not contain the completed cutover state",
        "repair_state_store",
        { databasePathAbs, status: cutover?.status },
      );
    }
  }
  return {
    ok: true,
    projectName,
    databasePathAbs,
    schemaVersion: migration.schemaVersion,
    database,
    close: () => database.close(),
  };
}
