// @ts-nocheck
const node_crypto_1 = require("node:crypto");
const node_fs_1 = require("node:fs");
const node_path_1 = { default: require("node:path") };
const suite_state_store_1 = require("../suite_state_store");
const correlation_state_store_1 = require("../correlation_state_store");
const external_verification_state_store_1 = require("../external_verification_state_store");
const artifact_state_store_1 = require("../artifact_state_store");
const watcher_state_store_1 = require("../watcher_state_store");
function isRecord(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function asRecord(value) {
  return isRecord(value) ? value : undefined;
}
function asRecordArray(value) {
  return Array.isArray(value) ? value.filter(isRecord) : [];
}
function asString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}
function asEpoch(value) {
  if (typeof value === "number" && Number.isInteger(value)) return value;
  if (typeof value === "string") {
    const parsed = Date.parse(value);
    if (Number.isInteger(parsed) && parsed > 0) return parsed;
  }
  return undefined;
}
async function checksum(filePathAbs) {
  return (0, node_crypto_1.createHash)("sha256")
    .update(await node_fs_1.promises.readFile(filePathAbs))
    .digest("hex");
}
function relativeToWorkspace(workspaceRootAbs, filePathAbs) {
  return node_path_1.default.relative(workspaceRootAbs, filePathAbs).replaceAll("\\", "/");
}
function workspaceRootFromDatabase(databasePathAbs) {
  return node_path_1.default.dirname(
    node_path_1.default.dirname(node_path_1.default.dirname(databasePathAbs)),
  );
}
function failedStepCount(execution) {
  return asRecordArray(execution.steps).filter((step) => {
    const status = asString(step.status);
    return (
      status !== undefined && !["pass", "passed", "ok", "skipped_condition_false"].includes(status)
    );
  }).length;
}
function buildPlanRun(source) {
  const status = asString(source.execution.status);
  const runStatus =
    status === "pass" || status === "fail" || status === "blocked" || status === "in_progress"
      ? status
      : "blocked";
  const steps = asRecordArray(source.execution.steps);
  const startedAtEpochMs = asEpoch(source.execution.startedAt);
  const completedAtEpochMs =
    runStatus === "in_progress" ? undefined : asEpoch(source.execution.endedAt);
  const projection = {
    planName: source.planName,
    runId: source.runId,
    status: "executed",
    runDirPathRel: source.runDirPathRel,
    runStatus,
    stepCount: steps.length,
    failedStepCount: failedStepCount(source.execution),
    ...(startedAtEpochMs !== undefined ? { startedAtEpochMs } : {}),
    ...(completedAtEpochMs !== undefined ? { completedAtEpochMs } : {}),
  };
  const reasonCode = asString(source.execution.reasonCode);
  if (reasonCode) projection.reasonCode = reasonCode;
  return projection;
}

async function readJsonRecord(filePathAbs) {
  try {
    const parsed = JSON.parse(await node_fs_1.promises.readFile(filePathAbs, "utf8"));
    return asRecord(parsed);
  } catch {
    return undefined;
  }
}
function insertPlanRun(store, planRun) {
  store.database
    .prepare(
      `
    INSERT INTO plan_runs (
      project_name, plan_name, run_id, status, plan_order, step_count,
      failed_step_count, started_at_epoch_ms, completed_at_epoch_ms,
      reason_code, run_dir_path_rel
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(project_name, plan_name, run_id) DO UPDATE SET
      status = excluded.status,
      plan_order = excluded.plan_order,
      step_count = excluded.step_count,
      failed_step_count = excluded.failed_step_count,
      started_at_epoch_ms = excluded.started_at_epoch_ms,
      completed_at_epoch_ms = excluded.completed_at_epoch_ms,
      reason_code = excluded.reason_code,
      run_dir_path_rel = excluded.run_dir_path_rel
  `,
    )
    .run(
      store.projectName,
      planRun.planName,
      planRun.runId,
      planRun.status,
      planRun.planOrder ?? null,
      planRun.stepCount ?? null,
      planRun.failedStepCount ?? null,
      planRun.startedAtEpochMs ?? null,
      planRun.completedAtEpochMs ?? null,
      planRun.reasonCode ?? null,
      planRun.runDirPathRel,
    );
}
function mapWatcher(source, watcher, watcherIndex) {
  const id = asString(watcher.id);
  const providerType = asString(watcher.providerType);
  const waitPolicy = asRecord(watcher.waitPolicy);
  const status = watcher.status;
  const outcome = watcher.outcome;
  const timeoutMs = waitPolicy?.timeoutMs;
  const retryMax = waitPolicy?.retryMax;
  const pollIntervalMs =
    waitPolicy?.pollIntervalMs ??
    (typeof timeoutMs === "number" && typeof retryMax === "number"
      ? Math.max(25, Math.floor(timeoutMs / retryMax))
      : undefined);
  if (
    !id ||
    !providerType ||
    !waitPolicy ||
    !["in_progress", "pass", "fail_assertion", "blocked_dependency", "blocked_runtime"].includes(
      String(status),
    ) ||
    !["verified", "failed_expectation", "timed_out", "blocked"].includes(String(outcome)) ||
    typeof timeoutMs !== "number" ||
    typeof retryMax !== "number" ||
    typeof pollIntervalMs !== "number"
  )
    return undefined;
  const startedAtEpochMs =
    asEpoch(watcher.startedAtEpochMs) ?? asEpoch(source.execution.startedAt) ?? Date.now();
  const completedAtEpochMs =
    asString(source.execution.status) === "in_progress"
      ? undefined
      : (asEpoch(source.execution.endedAt) ?? startedAtEpochMs);
  const attempts = [];
  for (const attempt of asRecordArray(watcher.attempts)) {
    const attemptNumber = typeof attempt.attempt === "number" ? attempt.attempt : 0;
    if (attemptNumber <= 0) continue;
    const normalizedAttempt = {
      attemptNumber,
      observedAtEpochMs: asEpoch(attempt.observedAt) ?? startedAtEpochMs,
      status: asString(attempt.status) ?? "blocked",
    };
    const reasonCode = asString(attempt.reasonCode);
    if (reasonCode) normalizedAttempt.reasonCode = reasonCode;
    if (typeof attempt.durationMs === "number") normalizedAttempt.durationMs = attempt.durationMs;
    attempts.push(normalizedAttempt);
  }
  const projection = {
    planName: source.planName,
    runId: source.runId,
    watcherName: id,
    dependencyStepOrder:
      typeof watcher.dependencyStepOrder === "number" ? watcher.dependencyStepOrder : 1,
    watcherIndex,
    providerType,
    status: status,
    outcome: outcome,
    startedAtEpochMs,
    deadlineAtEpochMs:
      typeof watcher.deadlineAtEpochMs === "number"
        ? watcher.deadlineAtEpochMs
        : startedAtEpochMs + timeoutMs,
    ...(completedAtEpochMs !== undefined ? { completedAtEpochMs } : {}),
    timeoutMs,
    pollIntervalMs,
    retryMax,
    attemptCount: typeof watcher.attemptCount === "number" ? watcher.attemptCount : attempts.length,
    ...(typeof watcher.lastObservation === "object" && watcher.lastObservation
      ? { lastObservation: watcher.lastObservation }
      : {}),
    ...(Array.isArray(watcher.assertions)
      ? { lastAssertion: { assertions: watcher.assertions } }
      : {}),
    artifactPathRel: source.runDirPathRel,
    ...(attempts.length > 0 ? { attempts } : {}),
  };
  const reasonCode = asString(watcher.reasonCode);
  if (reasonCode) projection.reasonCode = reasonCode;
  return projection;
}
function rebuildCorrelation(args) {
  const correlation = asRecord(args.source.correlation);
  if (!correlation) return;
  const window = asRecord(correlation.window);
  const sessionId = asString(correlation.correlationSessionId);
  const keyType = correlation.keyType;
  if (!window || !sessionId || !["traceId", "requestId", "messageId"].includes(String(keyType)))
    return;
  const timeline = asRecordArray(correlation.timeline);
  const expectations = timeline.map((entry) => ({
    strictLineKey: asString(entry.lineKey) ?? "",
    sequenceOrder: typeof entry.sequenceOrder === "number" ? entry.sequenceOrder : 0,
    selectorPolicy: entry.selectorPolicy,
    operator: entry.operator,
    ...(typeof entry.expectedHitDelta === "number"
      ? { expectedHitDelta: entry.expectedHitDelta }
      : {}),
    ...(typeof entry.expectedMinHitDelta === "number"
      ? { expectedMinHitDelta: entry.expectedMinHitDelta }
      : {}),
    ...(typeof entry.expectedMaxHitDelta === "number"
      ? { expectedMaxHitDelta: entry.expectedMaxHitDelta }
      : {}),
  }));
  const session = {
    planName: args.source.planName,
    runId: args.source.runId,
    correlationSessionId: sessionId,
    keyType: keyType,
    maxWindowMs: typeof window.maxWindowMs === "number" ? window.maxWindowMs : 1,
    startedAtEpochMs: typeof window.startEpochMs === "number" ? window.startEpochMs : Date.now(),
    status: correlation.status === "ok" ? "correlated" : "fail_closed",
    reasonCode: asString(correlation.reasonCode) ?? "state_store_rebuild",
    correlationPathRel: relativeToWorkspace(
      workspaceRootFromDatabase(args.store.databasePathAbs),
      node_path_1.default.join(args.source.runDirAbs, "correlation", "correlation.json"),
    ),
    expectations,
  };
  const keyValue = asString(correlation.keyValue);
  if (keyValue) session.keyValue = keyValue;
  const persisted = (0, correlation_state_store_1.persistCorrelationSession)({
    store: args.store,
    projectName: args.store.projectName,
    session,
  });
  if (!persisted.ok) return;
  for (const entry of timeline) {
    const observation = {
      strictLineKey: asString(entry.lineKey) ?? "",
      sequenceOrder: typeof entry.sequenceOrder === "number" ? entry.sequenceOrder : 0,
      selectorPolicy: entry.selectorPolicy,
      operator: entry.operator,
      ...(typeof entry.expectedHitDelta === "number"
        ? { expectedHitDelta: entry.expectedHitDelta }
        : {}),
      ...(typeof entry.expectedMinHitDelta === "number"
        ? { expectedMinHitDelta: entry.expectedMinHitDelta }
        : {}),
      ...(typeof entry.expectedMaxHitDelta === "number"
        ? { expectedMaxHitDelta: entry.expectedMaxHitDelta }
        : {}),
      probeId: asString(entry.probeId) ?? "",
      runtimeInstanceId: asString(entry.runtimeInstanceId) ?? "",
      baselineHitCount: typeof entry.baselineHitCount === "number" ? entry.baselineHitCount : 0,
      currentHitCount: typeof entry.currentHitCount === "number" ? entry.currentHitCount : 0,
      observedAtEpochMs:
        typeof entry.timestampEpochMs === "number" ? entry.timestampEpochMs : Date.now(),
    };
    if (
      (0, correlation_state_store_1.upsertCorrelationObservation)({
        store: args.store,
        projectName: args.store.projectName,
        planName: args.source.planName,
        runId: args.source.runId,
        correlationSessionId: sessionId,
        maxWindowMs: session.maxWindowMs,
        observation,
      }).ok
    )
      args.summary.rebuiltCorrelations += 1;
  }
}
async function rebuildSource(args) {
  const planRun = buildPlanRun(args.source);
  insertPlanRun(args.store, planRun);
  for (const file of args.source.files) {
    const link = (0, artifact_state_store_1.upsertRunStateArtifact)(args.store, {
      artifactKind: file.kind,
      pathRel: relativeToWorkspace(
        workspaceRootFromDatabase(args.store.databasePathAbs),
        file.pathAbs,
      ),
      planName: args.source.planName,
      runId: args.source.runId,
      checksum: await checksum(file.pathAbs),
      createdAtEpochMs: Math.trunc((await node_fs_1.promises.stat(file.pathAbs)).mtimeMs),
    });
    if (!link.ok) throw new Error(link.reasonCode);
  }
  for (const [watcherIndex, watcher] of asRecordArray(args.source.execution.watchers).entries()) {
    const projection = mapWatcher(args.source, watcher, watcherIndex);
    if (!projection) continue;
    const persisted = (0, watcher_state_store_1.upsertWatcherRun)({
      store: args.store,
      projectName: args.store.projectName,
      projection,
    });
    if (!persisted.ok) throw new Error(persisted.reasonCode);
    args.summary.rebuiltWatchers += 1;
  }
  for (const [verificationOrder, result] of asRecordArray(
    args.source.evidence.externalVerificationExecutions,
  ).entries()) {
    const providerType = result.providerType;
    const status = result.status;
    if (
      (providerType !== "http" && providerType !== "sql") ||
      !["pass", "fail_assertion", "blocked_runtime"].includes(String(status))
    )
      continue;
    const projection = {
      planName: args.source.planName,
      runId: args.source.runId,
      verificationName: asString(result.id) ?? `verification-${verificationOrder}`,
      verificationOrder,
      providerType,
      status: status,
      ...(typeof result.connectionRef === "string" ? { connectionRef: result.connectionRef } : {}),
      ...(result.requestSummary ? { requestSummary: result.requestSummary } : {}),
      ...(result.response
        ? { responseSummary: result.response }
        : result.sql
          ? { responseSummary: result.sql }
          : {}),
      ...(Array.isArray(result.assertions)
        ? {
            assertions: result.assertions.filter(isRecord).map((assertion) => ({
              id: asString(assertion.id) ?? "unknown",
              actualPath: asString(assertion.actualPath) ?? "unknown",
              operator: asString(assertion.operator) ?? "unknown",
              status: assertion.status,
              ...(typeof assertion.expected !== "undefined"
                ? { expected: assertion.expected }
                : {}),
              ...(typeof assertion.actual !== "undefined" ? { actual: assertion.actual } : {}),
              ...(asString(assertion.reasonCode)
                ? { reasonCode: asString(assertion.reasonCode) }
                : {}),
            })),
          }
        : {}),
      artifactPathRel: relativeToWorkspace(
        workspaceRootFromDatabase(args.store.databasePathAbs),
        node_path_1.default.join(args.source.runDirAbs, "execution.result.json"),
      ),
      createdAtEpochMs: asEpoch(args.source.execution.startedAt) ?? Date.now(),
      updatedAtEpochMs: asEpoch(args.source.execution.endedAt) ?? Date.now(),
    };
    const reasonCode = asString(result.reasonCode);
    if (reasonCode) projection.reasonCode = reasonCode;
    const persisted = (0, external_verification_state_store_1.upsertExternalVerificationSummary)({
      store: args.store,
      projectName: args.store.projectName,
      projection,
    });
    if (!persisted.ok) throw new Error(persisted.reasonCode);
    args.summary.rebuiltExternalVerifications += 1;
  }
  const correlationPath = args.source.files.find((file) => file.kind === "correlation");
  if (correlationPath) {
    const correlation = await readJsonRecord(correlationPath.pathAbs);
    if (correlation)
      rebuildCorrelation({
        store: args.store,
        source: { ...args.source, correlation },
        summary: args.summary,
      });
  }
  args.summary.rebuiltRuns += 1;
}
async function rebuildSuiteArtifact(args) {
  const executionProfile = asString(args.suite.executionProfile);
  const status = args.suite.status;
  const executionPolicy = args.suite.executionPolicy;
  if (
    !executionProfile ||
    !["pass", "fail", "blocked", "partial_fail", "in_progress"].includes(String(status)) ||
    !["stop_on_fail", "continue_on_fail"].includes(String(executionPolicy))
  ) {
    throw new Error("suite_result_invalid");
  }
  const planRuns = [];
  for (const entry of asRecordArray(args.suite.planRuns)) {
    const planName = asString(entry.planName);
    const runId = asString(entry.runId);
    if (!planName || !runId) continue;
    const source = args.sourcesByIdentity.get(`${planName}\u0000${runId}`);
    if (!source) continue;
    const planRun = buildPlanRun(source);
    if (typeof entry.order === "number") planRun.planOrder = entry.order;
    planRuns.push(planRun);
  }
  const firstStartedAt =
    planRuns.find((entry) => entry.startedAtEpochMs !== undefined)?.startedAtEpochMs ?? Date.now();
  const checkpoint = {
    suiteRunId: args.suiteRunId,
    executionProfile,
    status: status,
    startedAtEpochMs: firstStartedAt,
    updatedAtEpochMs: asEpoch(args.suite.updatedAt) ?? Date.now(),
    ...(typeof args.suite.nextPlanOrder === "number"
      ? { nextPlanOrder: args.suite.nextPlanOrder }
      : {}),
  };
  const reasonCode = asString(args.suite.reasonCode);
  if (reasonCode) checkpoint.reasonCode = reasonCode;
  const persisted = (0, suite_state_store_1.persistRegressionSuiteState)({
    store: args.store,
    checkpoint,
    planRuns,
  });
  if (!persisted.ok) throw new Error(persisted.reasonCode);
  const suitePathAbs = node_path_1.default.join(
    args.workspaceRootAbs,
    ".mcpjvm",
    args.projectName,
    "suite-runs",
    args.suiteRunId,
    "execution_orchestration.result.json",
  );
  const linked = (0, artifact_state_store_1.upsertRunStateArtifact)(args.store, {
    artifactKind: "execution_orchestration",
    pathRel: relativeToWorkspace(args.workspaceRootAbs, suitePathAbs),
    suiteRunId: args.suiteRunId,
    checksum: await checksum(suitePathAbs),
    createdAtEpochMs: Math.trunc((await node_fs_1.promises.stat(suitePathAbs)).mtimeMs),
  });
  if (!linked.ok) throw new Error(linked.reasonCode);
}

async function rebuildCanonicalState(args) {
  if (args.kind === "run") return rebuildSource(args);
  return rebuildSuiteArtifact(args);
}
export { rebuildCanonicalState };
