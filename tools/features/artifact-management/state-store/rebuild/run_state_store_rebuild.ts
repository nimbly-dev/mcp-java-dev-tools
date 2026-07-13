const node_fs_1 = require("node:fs");
const node_path_1 = { default: require("node:path") };
const run_state_store_schema_1 = require("../run_state_store_schema");
const suite_state_store_1 = require("../suite_state_store");
const correlation_state_store_1 = require("../correlation_state_store");
const external_verification_state_store_1 = require("../external_verification_state_store");
const artifact_state_store_1 = require("../artifact_state_store");
const watcher_state_store_1 = require("../watcher_state_store");
const run_state_store_rebuild_scan_1 = require("./run_state_store_rebuild_scan");
const run_state_store_rebuild_projection_1 = require("./run_state_store_rebuild_projection");
const { DatabaseSync } = require("node:sqlite");
const MAX_REASONS = 100;
function isRecord(value: unknown): value is Record<string, any> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function asRecord(value: unknown): any {
  return isRecord(value) ? value : undefined;
}
function asRecordArray(value: unknown): any[] {
  return Array.isArray(value) ? value.filter(isRecord) : [];
}
function asString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}
function asEpoch(value: unknown): number | undefined {
  if (typeof value === "number" && Number.isInteger(value)) return value;
  if (typeof value === "string") {
    const parsed = Date.parse(value);
    if (Number.isInteger(parsed) && parsed > 0) return parsed;
  }
  return undefined;
}
function safeRelativePath(pathRel: string): boolean {
  return (
    pathRel.length > 0 &&
    !node_path_1.default.isAbsolute(pathRel) &&
    !node_path_1.default.win32.isAbsolute(pathRel) &&
    !pathRel.split(/[\\/]/).includes("..")
  );
}
function failure(reasonCode: any, reason: string, nextAction: any, reasonMeta?: any): any {
  return { ok: false, reasonCode, reason, nextAction, ...(reasonMeta ? { reasonMeta } : {}) };
}
async function readJsonRecord(filePathAbs: string): Promise<any> {
  try {
    const parsed = JSON.parse(await node_fs_1.promises.readFile(filePathAbs, "utf8"));
    return asRecord(parsed);
  } catch {
    return undefined;
  }
}
function openCandidate(databasePathAbs: string, projectName: string): any {
  const database = new DatabaseSync(databasePathAbs);
  const migration = (0, run_state_store_schema_1.applyRunStateStoreMigrations)(
    database,
    projectName,
    databasePathAbs,
  );
  if (!migration.ok) {
    database.close();
    throw new Error(migration.reasonCode);
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
async function rebuildRunStateStore(args: any): Promise<any> {
  const projectName = args.projectName.trim();
  if (!projectName || projectName === "." || projectName === ".." || /[\\/]/.test(projectName))
    return failure(
      "state_store_rebuild_source_invalid",
      "projectName must be a non-empty path segment",
      "correct_state_store_input",
    );
  const projectDirAbs = node_path_1.default.join(
    node_path_1.default.resolve(args.workspaceRootAbs, ".mcpjvm"),
    projectName,
  );
  const databasePathAbs = node_path_1.default.join(projectDirAbs, "run-state.sqlite");
  const lockPathAbs = node_path_1.default.join(projectDirAbs, "run-state.rebuild.lock");
  const tempPathAbs = node_path_1.default.join(
    projectDirAbs,
    `run-state.sqlite.rebuild-${process.pid}-${Date.now()}`,
  );
  const summary = {
    scannedRuns: 0,
    rebuiltRuns: 0,
    skippedRuns: 0,
    invalidRuns: 0,
    conflictingRuns: 0,
    rebuiltCorrelations: 0,
    rebuiltWatchers: 0,
    rebuiltExternalVerifications: 0,
    nonReconstructibleActiveStates: 0,
  };
  const reasons: any[] = [];
  const reasonCount = { value: 0 };
  let rebuiltSourceCount = 0;
  let lockHandle;
  let candidate;
  try {
    await node_fs_1.promises.mkdir(projectDirAbs, { recursive: true });
    try {
      lockHandle = await node_fs_1.promises.open(lockPathAbs, "wx");
    } catch {
      return failure(
        "state_store_rebuild_conflict",
        "another state-store rebuild is active",
        "retry_state_store",
        { projectName },
      );
    }
    const sources = await run_state_store_rebuild_scan_1.scanRunStateSources({
      workspaceRootAbs: args.workspaceRootAbs,
      projectName,
      summary,
      reasons,
      reasonCount,
    });
    if (sources.some((source: any) => source.execution.status === "in_progress")) {
      summary.nonReconstructibleActiveStates += 1;
      return failure(
        "state_store_rebuild_active_runs",
        "active plan execution cannot be reconstructed safely",
        "retry_state_store",
      );
    }
    candidate = openCandidate(tempPathAbs, projectName);
    const cutoverMarkerAbs = node_path_1.default.join(projectDirAbs, "state-store.cutover.json");
    const wasCutover = await node_fs_1.promises
      .stat(cutoverMarkerAbs)
      .then(() => true)
      .catch(() => false);
    if (wasCutover) {
      candidate.database
        .prepare(
          `
          INSERT INTO state_store_cutover (
            project_name, status, transition_revision, updated_at_epoch_ms, completed_at_epoch_ms
          ) VALUES (?, 'cutover_complete', 1, ?, ?)
          ON CONFLICT(project_name) DO UPDATE SET
            status = 'cutover_complete',
            transition_revision = MAX(state_store_cutover.transition_revision, excluded.transition_revision),
            updated_at_epoch_ms = excluded.updated_at_epoch_ms,
            completed_at_epoch_ms = COALESCE(state_store_cutover.completed_at_epoch_ms, excluded.completed_at_epoch_ms)
        `,
        )
        .run(projectName, Date.now(), Date.now());
    }
    const sourcesByIdentity = new Map(
      sources.map((source: any) => [`${source.planName}\u0000${source.runId}`, source]),
    );
    const suiteRoot = node_path_1.default.join(projectDirAbs, "suite-runs");
    const suiteEntries = await node_fs_1.promises
      .readdir(suiteRoot, { withFileTypes: true })
      .catch(() => []);
    for (const entry of suiteEntries.filter((item: any) => item.isDirectory())) {
      const suitePathAbs = node_path_1.default.join(
        suiteRoot,
        entry.name,
        "execution_orchestration.result.json",
      );
      const suite = await readJsonRecord(suitePathAbs);
      if (suite?.status === "in_progress") {
        summary.nonReconstructibleActiveStates += 1;
        return failure(
          "state_store_rebuild_active_runs",
          "active suite execution cannot be reconstructed safely",
          "retry_state_store",
          { suiteRunId: entry.name },
        );
      }
      if (suite) {
        try {
          await run_state_store_rebuild_projection_1.rebuildCanonicalState({
            kind: "suite",
            store: candidate,
            workspaceRootAbs: args.workspaceRootAbs,
            projectName,
            suiteRunId: entry.name,
            suite,
            sourcesByIdentity,
          });
          rebuiltSourceCount += 1;
        } catch (error) {
          summary.invalidRuns += 1;
          reasonCount.value += 1;
          if (reasons.length < MAX_REASONS)
            reasons.push({
              suiteRunId: entry.name,
              reasonCode: error instanceof Error ? error.message : String(error),
            });
          if (args.strict)
            return failure(
              "state_store_rebuild_source_invalid",
              "strict rebuild rejected an invalid suite Artifact",
              "correct_state_store_input",
              { reasons },
            );
        }
      }
    }
    for (const source of sources as any[]) {
      try {
        await run_state_store_rebuild_projection_1.rebuildCanonicalState({
          kind: "run",
          store: candidate,
          source,
          summary,
        });
        rebuiltSourceCount += 1;
      } catch (error) {
        summary.invalidRuns += 1;
        summary.skippedRuns += 1;
        reasonCount.value += 1;
        if (reasons.length < MAX_REASONS)
          reasons.push({
            planName: source.planName,
            runId: source.runId,
            reasonCode: error instanceof Error ? error.message : String(error),
          });
        if (args.strict)
          return failure(
            "state_store_rebuild_source_invalid",
            "strict rebuild rejected an invalid canonical run",
            "correct_state_store_input",
            { reasons },
          );
      }
    }
    if (rebuiltSourceCount === 0)
      return failure(
        "state_store_rebuild_source_invalid",
        "no usable canonical Artifact source was found for rebuild",
        "correct_state_store_input",
        { projectName },
      );
    if (
      args.strict &&
      (summary.invalidRuns > 0 ||
        summary.skippedRuns > 0 ||
        summary.conflictingRuns > 0 ||
        summary.nonReconstructibleActiveStates > 0)
    )
      return failure(
        "state_store_rebuild_source_invalid",
        "strict rebuild rejected incomplete or conflicting canonical Artifact sources",
        "correct_state_store_input",
        { projectName, summary },
      );
    const integrity = candidate.database.prepare("PRAGMA integrity_check").get();
    if (integrity?.integrity_check !== "ok")
      return failure(
        "state_store_rebuild_integrity_failed",
        "temporary rebuild database failed integrity validation",
        "rebuild_state_store",
        { integrity: integrity?.integrity_check },
      );
    const foreignKeys = candidate.database.prepare("PRAGMA foreign_key_check").all();
    if (foreignKeys.length > 0)
      return failure(
        "state_store_rebuild_integrity_failed",
        "temporary rebuild database failed foreign-key validation",
        "rebuild_state_store",
        { violations: foreignKeys.slice(0, MAX_REASONS) },
      );
    candidate.database.exec("PRAGMA wal_checkpoint(TRUNCATE);");
    candidate.close();
    candidate = undefined;
    const quarantinePathAbs = node_path_1.default.join(
      projectDirAbs,
      "state-store-backups",
      `run-state.sqlite.previous-${Date.now()}`,
    );
    await node_fs_1.promises.mkdir(node_path_1.default.dirname(quarantinePathAbs), {
      recursive: true,
    });
    const liveComponents = [databasePathAbs, `${databasePathAbs}-wal`, `${databasePathAbs}-shm`];
    const candidateComponents = [tempPathAbs, `${tempPathAbs}-wal`, `${tempPathAbs}-shm`];
    const quarantinedComponents = [];
    for (const component of liveComponents) {
      try {
        await node_fs_1.promises.stat(component);
        const target = `${quarantinePathAbs}${component.slice(databasePathAbs.length)}`;
        await node_fs_1.promises.rename(component, target);
        quarantinedComponents.push({ from: component, to: target });
      } catch {
        // Component is absent; this is normal for a database without a WAL sidecar.
      }
    }
    try {
      for (const component of candidateComponents) {
        try {
          await node_fs_1.promises.stat(component);
          await node_fs_1.promises.rename(
            component,
            `${databasePathAbs}${component.slice(tempPathAbs.length)}`,
          );
        } catch {
          // Candidate sidecars are optional after the explicit checkpoint.
        }
      }
    } catch (error) {
      for (const component of quarantinedComponents.reverse()) {
        await node_fs_1.promises.rename(component.to, component.from).catch(() => undefined);
      }
      return failure(
        "state_store_rebuild_replace_failed",
        "rebuilt SQLite store could not replace the live store",
        "retry_state_store",
        { error: error instanceof Error ? error.message : String(error) },
      );
    }
    return {
      ok: true,
      summary: {
        ...summary,
        ...(reasons.length > 0 ? { reasons } : {}),
        ...(reasonCount.value > reasons.length ? { reasonsTruncated: true } : {}),
      },
      databasePathAbs,
      ...(quarantinedComponents.length > 0 ? { quarantinePathAbs } : {}),
    };
  } catch (error) {
    return failure(
      "state_store_rebuild_failed",
      "SQLite state-store rebuild failed",
      "rebuild_state_store",
      { error: error instanceof Error ? error.message : String(error), summary, reasons },
    );
  } finally {
    candidate?.close();
    await lockHandle?.close().catch(() => undefined);
    await node_fs_1.promises.unlink(lockPathAbs).catch(() => undefined);
    await node_fs_1.promises.unlink(tempPathAbs).catch(() => undefined);
  }
}
//# sourceMappingURL=run_state_store_rebuild.js.map

export { rebuildRunStateStore };
