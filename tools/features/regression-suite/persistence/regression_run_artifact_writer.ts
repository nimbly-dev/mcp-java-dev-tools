/**
 * Regression run persistence owner.
 *
 * This module preserves the existing result/evidence normalization,
 * correlation-index maintenance, path resolution, and Artifact write
 * behavior behind one Feature-local persistence boundary.
 */
import { promises as fs } from "node:fs";
import { readdirSync, statSync, type Dirent } from "node:fs";
import path from "node:path";
import {
  openRunStateStore,
  persistCorrelationSession,
  upsertExternalVerificationSummary,
  upsertCorrelationObservation,
  upsertWatcherRun,
} from "@tools-feature-artifact-management";

import type {
  CorrelationIndexRebuildResult,
  CorrelationArtifact,
  RegressionRunArtifactsWriteResult,
  WriteRegressionRunArtifactsInput,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_run_artifact.model";
import { resolveRegressionPlansRootAbs } from "../../../spec/regression-execution-plan-spec/src/regression_artifact_paths.util";
import { correlateEvents } from "../shared/regression_correlation";
import {
  buildResolvedSecretRedactionMeta,
  sanitizeSuitePersistedContext,
} from "../shared/suite_context_redaction";

export type {
  CorrelationIndexRebuildResult,
  RegressionPlanReference,
  RegressionRunArtifactsWriteResult,
  RegressionRunExecutionResult,
  RegressionRunStatus,
  WriteRegressionRunArtifactsInput,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_run_artifact.model";

const RUN_ID_PATTERN =
  /^(?:\d{2}-\d{2}-\d{4}-\d{2}-\d{2}-\d{2}(?:AM|PM)|\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}Z_\d{2}|\d{10,})$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

import {
  normalizeExecutionResultPayload,
  normalizeEvidencePayload,
  normalizeCorrelationPayload,
  stripRedundantResolvedContextFields,
} from "./normalize_regression_run_artifacts";

function asCorrelationKeyType(value: unknown): "traceId" | "requestId" | "messageId" {
  return value === "requestId" ? "requestId" : value === "messageId" ? "messageId" : "traceId";
}

function toCorrelationArtifactFromEvidence(args: {
  evidence: Record<string, unknown>;
  resolvedContext: Record<string, unknown>;
  now: Date;
}): CorrelationArtifact | undefined {
  const policyRaw = args.evidence.correlationPolicy;
  const eventsRaw = args.evidence.correlationEvents;
  if (!isRecord(policyRaw) || !Array.isArray(eventsRaw)) return undefined;

  const keyType = asCorrelationKeyType(policyRaw.keyType);
  const maxWindowMs =
    typeof policyRaw.maxWindowMs === "number" && Number.isFinite(policyRaw.maxWindowMs)
      ? policyRaw.maxWindowMs
      : 0;
  const expectedFlow = Array.isArray(policyRaw.expectedFlow)
    ? policyRaw.expectedFlow.map((value) => String(value))
    : undefined;

  const keyValueRaw = policyRaw.keyValue;
  const keyFromContextPath =
    typeof policyRaw.keyValueContextPath === "string" ? policyRaw.keyValueContextPath : undefined;
  const keyFromContext =
    keyFromContextPath && typeof args.resolvedContext[keyFromContextPath] !== "undefined"
      ? String(args.resolvedContext[keyFromContextPath])
      : undefined;
  const keyValue =
    typeof keyValueRaw === "string" && keyValueRaw.trim().length > 0 ? keyValueRaw : keyFromContext;
  const keySourceType =
    typeof policyRaw.keySourceType === "string" ? policyRaw.keySourceType : undefined;
  const keySourcePath =
    typeof policyRaw.keySourcePath === "string" ? policyRaw.keySourcePath : undefined;
  const keyExtractionReasonCode =
    policyRaw.keyExtractionReasonCode === "correlation_key_extraction_failed"
      ? "correlation_key_extraction_failed"
      : undefined;

  const correlationEvents = eventsRaw
    .filter((entry) => isRecord(entry))
    .map((entry) => {
      const event = entry as Record<string, unknown>;
      return {
        eventId: String(event.eventId ?? ""),
        probeId: String(event.probeId ?? ""),
        timestampEpochMs: Number(event.timestampEpochMs ?? 0),
        keyType: asCorrelationKeyType(event.keyType),
        ...(typeof event.keyValue === "string" ? { keyValue: event.keyValue } : {}),
        ...(typeof event.lineKey === "string" ? { lineKey: event.lineKey } : {}),
        ...(typeof event.sequenceOrder === "number" ? { sequenceOrder: event.sequenceOrder } : {}),
        ...(event.selectorPolicy === "exact_instance" ||
        event.selectorPolicy === "any_instance" ||
        event.selectorPolicy === "all_instances" ||
        event.selectorPolicy === "aggregate" ||
        event.selectorPolicy === "quorum"
          ? { selectorPolicy: event.selectorPolicy }
          : {}),
        ...(event.operator === "exact" ||
        event.operator === "at_least" ||
        event.operator === "at_most" ||
        event.operator === "range"
          ? { operator: event.operator }
          : {}),
        ...(typeof event.expectedHitDelta === "number"
          ? { expectedHitDelta: event.expectedHitDelta }
          : {}),
        ...(typeof event.expectedMinHitDelta === "number"
          ? { expectedMinHitDelta: event.expectedMinHitDelta }
          : {}),
        ...(typeof event.expectedMaxHitDelta === "number"
          ? { expectedMaxHitDelta: event.expectedMaxHitDelta }
          : {}),
        ...(typeof event.runtimeInstanceId === "string"
          ? { runtimeInstanceId: event.runtimeInstanceId }
          : {}),
        ...(typeof event.baselineHitCount === "number"
          ? { baselineHitCount: event.baselineHitCount }
          : {}),
        ...(typeof event.currentHitCount === "number"
          ? { currentHitCount: event.currentHitCount }
          : {}),
      };
    })
    .filter((event) => event.eventId && event.probeId && Number.isFinite(event.timestampEpochMs));

  if (typeof keyValue !== "string" || keyValue.trim().length === 0) {
    return {
      status: "fail_closed",
      reasonCode: keyExtractionReasonCode ?? "missing_correlation_key",
      ...(keyExtractionReasonCode && (keySourceType || keySourcePath)
        ? {
            reasonMeta: {
              ...(keySourceType ? { sourceType: keySourceType } : {}),
              ...(keySourcePath ? { sourcePath: keySourcePath } : {}),
            },
          }
        : {}),
      keyType,
      window: { maxWindowMs: maxWindowMs > 0 ? maxWindowMs : 0 },
      timeline: [],
      generatedAtEpochMs: args.now.getTime(),
    };
  }

  const matched = correlateEvents(correlationEvents, {
    keyType,
    keyValue,
    maxWindowMs,
    ...(Array.isArray(expectedFlow) ? { expectedFlow } : {}),
  });

  const timeline = matched.timeline.map((event) => ({
    eventId: event.eventId,
    probeId: event.probeId,
    timestampEpochMs: event.timestampEpochMs,
    ...(typeof event.lineKey === "string" ? { lineKey: event.lineKey } : {}),
  }));

  return {
    status: matched.status === "ok" ? "ok" : "fail_closed",
    reasonCode: matched.reasonCode === "ok" ? "ok" : matched.reasonCode,
    ...(typeof policyRaw.correlationSessionId === "string"
      ? { correlationSessionId: policyRaw.correlationSessionId }
      : {}),
    keyType,
    keyValue,
    window: {
      ...(typeof policyRaw.startEpochMs === "number"
        ? { startEpochMs: policyRaw.startEpochMs }
        : {}),
      ...(typeof policyRaw.endEpochMs === "number" ? { endEpochMs: policyRaw.endEpochMs } : {}),
      maxWindowMs,
    },
    ...(Array.isArray(expectedFlow) ? { expectedFlow } : {}),
    ...(Array.isArray(policyRaw.strictLineExpectations)
      ? {
          strictLineExpectations: policyRaw.strictLineExpectations as unknown as NonNullable<
            CorrelationArtifact["strictLineExpectations"]
          >,
        }
      : {}),
    timeline,
    generatedAtEpochMs: args.now.getTime(),
  };
}

function asCorrelationVerdict(value: unknown): "ok" | "fail_closed" {
  return value === "ok" || value === "matched" ? "ok" : "fail_closed";
}

function asCorrelationReasonCode(value: unknown): string {
  return typeof value === "string" && value.trim().length > 0 ? value : "insufficient_evidence";
}

export async function rebuildCorrelationIndex(args: {
  workspaceRootAbs: string;
  projectName?: string;
  now?: Date;
}): Promise<CorrelationIndexRebuildResult> {
  return {
    ok: false,
    reasonCode: "legacy_write_disabled",
    reason: "correlation-index.json is read-only legacy input after SQLite cutover",
    nextAction: "use_sqlite_state_store",
  };
}

async function writeJsonFile(filePathAbs: string, payload: Record<string, unknown>): Promise<void> {
  await fs.writeFile(filePathAbs, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

function buildExternalVerificationResponseSummary(result: {
  response?: {
    statusCode?: number;
    bodyFormat?: string;
    bodyBytes?: number;
    hasBodyJson?: boolean;
    headerNames?: string[];
  };
  sql?: {
    rowCount: number;
    firstRow?: Record<string, unknown>;
    rows: Record<string, unknown>[];
  };
}): Record<string, unknown> | undefined {
  if (result.response) {
    return {
      ...(typeof result.response.statusCode === "number"
        ? { statusCode: result.response.statusCode }
        : {}),
      ...(typeof result.response.bodyFormat === "string"
        ? { bodyFormat: result.response.bodyFormat }
        : {}),
      ...(typeof result.response.bodyBytes === "number"
        ? { bodyBytes: result.response.bodyBytes }
        : {}),
      ...(typeof result.response.hasBodyJson === "boolean"
        ? { hasBodyJson: result.response.hasBodyJson }
        : {}),
      ...(Array.isArray(result.response.headerNames)
        ? { headerNames: result.response.headerNames.slice(0, 100) }
        : {}),
    };
  }
  if (result.sql) {
    return {
      rowCount: result.sql.rowCount,
      ...(result.sql.firstRow ? { firstRow: result.sql.firstRow } : {}),
      rows: result.sql.rows.slice(0, 25),
    };
  }
  return undefined;
}

async function resolveProjectRootAbs(args: {
  workspaceRootAbs: string;
  projectName?: string;
}): Promise<{ projectName: string; projectRootAbs: string }> {
  const plansRootAbs = await resolveRegressionPlansRootAbs(args.workspaceRootAbs, args.projectName);
  const projectRootAbs = path.dirname(path.dirname(plansRootAbs));
  return {
    projectName: path.basename(projectRootAbs),
    projectRootAbs,
  };
}

function normalizePlanName(planName: string): string {
  const normalized = planName.trim();
  if (!normalized) {
    throw new Error("plan_name_missing");
  }
  if (!/^[A-Za-z0-9._-]+$/.test(normalized)) {
    throw new Error("plan_name_invalid");
  }
  return normalized;
}

export function buildRunArtifactDirAbs(
  workspaceRootAbs: string,
  planName: string,
  runId: string,
): string {
  if (!workspaceRootAbs || workspaceRootAbs.trim() === "") {
    throw new Error("workspace_root_missing");
  }
  const safePlanName = normalizePlanName(planName);
  if (!RUN_ID_PATTERN.test(runId)) {
    throw new Error("run_id_invalid");
  }
  const mcpjvmRoot = path.join(workspaceRootAbs, ".mcpjvm");
  let projectName: string | null = null;
  try {
    const entries = readdirSync(mcpjvmRoot, { withFileTypes: true });
    const candidates = entries
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
      .filter((name) => {
        try {
          return statSync(path.join(mcpjvmRoot, name, "projects.json")).isFile();
        } catch {
          return false;
        }
      });
    if (candidates.length === 1) {
      projectName = candidates[0] ?? null;
    } else if (candidates.length === 0) {
      throw new Error("project_artifact_missing");
    } else {
      throw new Error("project_artifact_ambiguous");
    }
  } catch (error) {
    if (
      error instanceof Error &&
      (error.message === "project_artifact_missing" ||
        error.message === "project_artifact_ambiguous")
    ) {
      throw error;
    }
    throw new Error("project_artifact_missing");
  }
  return path.join(
    workspaceRootAbs,
    ".mcpjvm",
    String(projectName),
    "plans",
    "regression",
    safePlanName,
    "runs",
    runId,
  );
}

export async function writeRegressionRunArtifacts(
  args: WriteRegressionRunArtifactsInput,
): Promise<RegressionRunArtifactsWriteResult> {
  if (!args.planRef?.name) {
    throw new Error("plan_name_missing");
  }
  const plansRootAbs = await resolveRegressionPlansRootAbs(args.workspaceRootAbs, args.projectName);
  const runDirAbs = path.join(
    plansRootAbs,
    normalizePlanName(args.planRef.name),
    "runs",
    args.runId,
  );
  if (!RUN_ID_PATTERN.test(args.runId)) {
    throw new Error("run_id_invalid");
  }
  await fs.mkdir(runDirAbs, { recursive: true });

  const explicitSecretPaths = new Set(args.secretContextKeys ?? []);
  const now = args.now ?? new Date();
  const resolvedSecretRedactionMeta = buildResolvedSecretRedactionMeta({
    resolvedContext: args.resolvedContext,
    explicitSecretPaths,
  });

  const contextResolvedPathAbs = path.join(runDirAbs, "context.resolved.json");
  const executionResultPathAbs = path.join(runDirAbs, "execution.result.json");
  const evidencePathAbs = path.join(runDirAbs, "evidence.json");
  const correlationDirAbs = path.join(runDirAbs, "correlation");
  const correlationPathAbs = path.join(correlationDirAbs, "correlation.json");
  const persistenceWarnings: NonNullable<RegressionRunArtifactsWriteResult["persistenceWarnings"]> =
    [];

  const contextResolvedPayload = sanitizeSuitePersistedContext(
    {
      resolvedAt: now.toISOString(),
      ...(args.planRef ? { planRef: args.planRef } : {}),
      ...(resolvedSecretRedactionMeta ? { redaction: resolvedSecretRedactionMeta } : {}),
      ...stripRedundantResolvedContextFields(args.resolvedContext),
    },
    explicitSecretPaths,
  ) as Record<string, unknown>;

  const executionResultPayload = sanitizeSuitePersistedContext(
    {
      ...normalizeExecutionResultPayload(args.executionResult),
      ...(args.planRef ? { planRef: args.planRef } : {}),
      runId: args.runId,
      ...(typeof args.executionProfile === "string"
        ? { executionProfile: args.executionProfile }
        : {}),
      ...(typeof args.suiteRunId === "string" ? { suiteRunId: args.suiteRunId } : {}),
    },
    explicitSecretPaths,
  ) as Record<string, unknown>;

  const evidencePayload = sanitizeSuitePersistedContext(
    {
      ...normalizeEvidencePayload(args.evidence),
      ...(args.planRef ? { planRef: args.planRef } : {}),
      runId: args.runId,
    },
    explicitSecretPaths,
  ) as Record<string, unknown>;

  await writeJsonFile(contextResolvedPathAbs, contextResolvedPayload);
  await writeJsonFile(executionResultPathAbs, executionResultPayload);
  await writeJsonFile(evidencePathAbs, evidencePayload);

  const externalVerificationExecutions = args.evidence.externalVerificationExecutions ?? [];
  if (externalVerificationExecutions.length > 0) {
    const project = await resolveProjectRootAbs({
      workspaceRootAbs: args.workspaceRootAbs,
      ...(typeof args.projectName === "string" && args.projectName.trim()
        ? { projectName: args.projectName.trim() }
        : {}),
    });
    const store = await openRunStateStore({
      workspaceRootAbs: args.workspaceRootAbs,
      projectName: project.projectName,
    });
    if (!store.ok) {
      persistenceWarnings.push({
        reasonCode: store.reasonCode,
        reason: store.reason,
        nextAction: store.nextAction,
      });
    } else {
      try {
        const artifactPathRel = path
          .relative(args.workspaceRootAbs, executionResultPathAbs)
          .replaceAll("\\", "/");
        for (const [verificationOrder, result] of externalVerificationExecutions.entries()) {
          const reasonMeta = result.reasonMeta;
          const connectionRef =
            typeof result.connectionRef === "string"
              ? result.connectionRef
              : typeof reasonMeta?.connectionRef === "string"
                ? reasonMeta.connectionRef
                : undefined;
          const responseSummary = buildExternalVerificationResponseSummary(result);
          const persisted = upsertExternalVerificationSummary({
            store,
            projectName: project.projectName,
            projection: {
              planName: args.planRef.name,
              runId: args.runId,
              ...(args.suiteRunId ? { suiteRunId: args.suiteRunId } : {}),
              verificationName: result.id,
              verificationOrder,
              providerType: result.providerType,
              status: result.status,
              ...(result.reasonCode ? { reasonCode: result.reasonCode } : {}),
              ...(typeof result.response?.durationMs === "number"
                ? { durationMs: result.response.durationMs }
                : typeof result.sql?.durationMs === "number"
                  ? { durationMs: result.sql.durationMs }
                  : {}),
              ...(connectionRef ? { connectionRef } : {}),
              ...(result.requestSummary ? { requestSummary: result.requestSummary } : {}),
              ...(responseSummary ? { responseSummary } : {}),
              ...(result.assertions
                ? {
                    assertions: result.assertions.map((assertion) => ({
                      id: assertion.id,
                      actualPath: assertion.actualPath,
                      operator: assertion.operator,
                      status: assertion.status,
                      ...(typeof assertion.expected !== "undefined"
                        ? { expected: assertion.expected }
                        : {}),
                      ...(typeof assertion.actual !== "undefined"
                        ? { actual: assertion.actual }
                        : {}),
                      ...(assertion.reasonCode ? { reasonCode: assertion.reasonCode } : {}),
                    })),
                  }
                : {}),
              artifactPathRel,
              createdAtEpochMs: now.getTime(),
              updatedAtEpochMs: now.getTime(),
            },
          });
          if (!persisted.ok) {
            persistenceWarnings.push({
              reasonCode: persisted.reasonCode,
              reason: persisted.reason,
              nextAction: persisted.nextAction,
            });
          }
        }
      } finally {
        store.close();
      }
    }
  }

  let writtenCorrelationPathAbs: string | undefined;
  const correlation = args.correlation
    ? args.correlation
    : toCorrelationArtifactFromEvidence({
        evidence: args.evidence,
        resolvedContext: args.resolvedContext,
        now,
      });
  if (correlation) {
    await fs.mkdir(correlationDirAbs, { recursive: true });
    const correlationPayload = sanitizeSuitePersistedContext(
      normalizeCorrelationPayload(correlation),
      explicitSecretPaths,
    ) as Record<string, unknown>;
    await writeJsonFile(correlationPathAbs, correlationPayload);
    writtenCorrelationPathAbs = correlationPathAbs;
    const project = await resolveProjectRootAbs({
      workspaceRootAbs: args.workspaceRootAbs,
      ...(typeof args.projectName === "string" && args.projectName.trim()
        ? { projectName: args.projectName.trim() }
        : {}),
    });
    const store = await openRunStateStore({
      workspaceRootAbs: args.workspaceRootAbs,
      projectName: project.projectName,
    });
    if (!store.ok) throw new Error(store.reasonCode);
    try {
      const session = persistCorrelationSession({
        store,
        projectName: project.projectName,
        session: {
          planName: args.planRef.name,
          runId: args.runId,
          correlationSessionId: correlation.correlationSessionId ?? args.runId,
          keyType: correlation.keyType,
          ...(typeof correlation.keyValue === "string" ? { keyValue: correlation.keyValue } : {}),
          maxWindowMs: correlation.window.maxWindowMs,
          startedAtEpochMs:
            correlation.window.startEpochMs ?? correlation.generatedAtEpochMs ?? now.getTime(),
          status:
            correlation.status !== "ok"
              ? "fail_closed"
              : correlation.strictLineExpectations?.length
                ? "collecting"
                : "correlated",
          reasonCode:
            correlation.status !== "ok"
              ? correlation.reasonCode
              : correlation.strictLineExpectations?.length
                ? "collecting"
                : "ok",
          correlationPathRel: path
            .relative(args.workspaceRootAbs, correlationPathAbs)
            .replaceAll("\\", "/"),
          ...(Array.isArray(correlation.strictLineExpectations)
            ? { expectations: correlation.strictLineExpectations }
            : {}),
        },
      });
      if (!session.ok) throw new Error(session.reasonCode);
      for (const [index, event] of correlation.timeline.entries()) {
        const eventRecord = event as unknown as Record<string, unknown>;
        const runtimeInstanceId =
          typeof eventRecord.runtimeInstanceId === "string"
            ? eventRecord.runtimeInstanceId
            : undefined;
        const baselineHitCount =
          typeof eventRecord.baselineHitCount === "number"
            ? eventRecord.baselineHitCount
            : undefined;
        const currentHitCount =
          typeof eventRecord.currentHitCount === "number" ? eventRecord.currentHitCount : undefined;
        if (
          !event.lineKey ||
          !runtimeInstanceId ||
          baselineHitCount === undefined ||
          currentHitCount === undefined
        )
          continue;
        const operator =
          eventRecord.operator === "exact" ||
          eventRecord.operator === "at_most" ||
          eventRecord.operator === "range"
            ? eventRecord.operator
            : "at_least";
        const selectorPolicy =
          eventRecord.selectorPolicy === "exact_instance" ||
          eventRecord.selectorPolicy === "any_instance" ||
          eventRecord.selectorPolicy === "all_instances" ||
          eventRecord.selectorPolicy === "quorum"
            ? eventRecord.selectorPolicy
            : "aggregate";
        const persisted = upsertCorrelationObservation({
          store,
          projectName: project.projectName,
          planName: args.planRef.name,
          runId: args.runId,
          correlationSessionId: correlation.correlationSessionId ?? args.runId,
          maxWindowMs: correlation.window.maxWindowMs,
          observation: {
            strictLineKey: event.lineKey,
            sequenceOrder:
              typeof eventRecord.sequenceOrder === "number" ? eventRecord.sequenceOrder : index + 1,
            selectorPolicy,
            operator,
            ...(typeof eventRecord.expectedHitDelta === "number"
              ? { expectedHitDelta: eventRecord.expectedHitDelta }
              : { expectedHitDelta: 1 }),
            ...(typeof eventRecord.expectedMinHitDelta === "number"
              ? { expectedMinHitDelta: eventRecord.expectedMinHitDelta }
              : {}),
            ...(typeof eventRecord.expectedMaxHitDelta === "number"
              ? { expectedMaxHitDelta: eventRecord.expectedMaxHitDelta }
              : {}),
            probeId: event.probeId,
            runtimeInstanceId,
            baselineHitCount,
            currentHitCount,
            observedAtEpochMs: event.timestampEpochMs,
          },
        });
        if (!persisted.ok) throw new Error(persisted.reasonCode);
      }
    } finally {
      store.close();
    }
  }

  const activeWatcherContinuation =
    args.executionResult.continuation?.phase === "watchers"
      ? args.executionResult.continuation
      : undefined;
  if (
    (Array.isArray(args.executionResult.watchers) && args.executionResult.watchers.length > 0) ||
    activeWatcherContinuation
  ) {
    const project = await resolveProjectRootAbs({
      workspaceRootAbs: args.workspaceRootAbs,
      ...(typeof args.projectName === "string" && args.projectName.trim()
        ? { projectName: args.projectName.trim() }
        : {}),
    });
    const store = await openRunStateStore({
      workspaceRootAbs: args.workspaceRootAbs,
      projectName: project.projectName,
    });
    if (!store.ok) throw new Error(store.reasonCode);
    try {
      const startedAtEpochMs = Date.parse(args.executionResult.startedAt ?? "") || now.getTime();
      const artifactPathRel = path.relative(args.workspaceRootAbs, runDirAbs).replaceAll("\\", "/");
      for (const [watcherIndex, watcher] of (args.executionResult.watchers ?? []).entries()) {
        const timeoutMs = watcher.waitPolicy.timeoutMs;
        const pollIntervalMs =
          watcher.waitPolicy.pollIntervalMs ??
          (typeof watcher.waitPolicy.timeoutMs === "number" &&
          typeof watcher.waitPolicy.retryMax === "number"
            ? Math.max(25, Math.floor(watcher.waitPolicy.timeoutMs / watcher.waitPolicy.retryMax))
            : undefined);
        const retryMax = watcher.waitPolicy.retryMax;
        if (
          typeof timeoutMs !== "number" ||
          typeof pollIntervalMs !== "number" ||
          typeof retryMax !== "number"
        ) {
          throw new Error("watcher_checkpoint_invalid");
        }
        const attempts = (watcher.attempts ?? []).map((attempt) => ({
          attemptNumber: attempt.attempt,
          observedAtEpochMs: Date.parse(attempt.observedAt) || startedAtEpochMs,
          status: attempt.status,
          ...(attempt.reasonCode ? { reasonCode: attempt.reasonCode } : {}),
          ...(typeof attempt.durationMs === "number" ? { durationMs: attempt.durationMs } : {}),
        }));
        const watcherStartedAtEpochMs = watcher.startedAtEpochMs ?? startedAtEpochMs;
        const watcherCompletedAtEpochMs = Math.max(
          watcherStartedAtEpochMs,
          Date.parse(args.executionResult.endedAt ?? "") || now.getTime(),
        );
        const persisted = upsertWatcherRun({
          store,
          projectName: project.projectName,
          projection: {
            planName: args.planRef.name,
            runId: args.runId,
            ...(args.suiteRunId ? { suiteRunId: args.suiteRunId } : {}),
            watcherName: watcher.id,
            dependencyStepOrder: watcher.dependencyStepOrder,
            watcherIndex,
            providerType: watcher.providerType,
            status: watcher.status,
            outcome: watcher.outcome,
            ...(watcher.reasonCode ? { reasonCode: watcher.reasonCode } : {}),
            startedAtEpochMs: watcherStartedAtEpochMs,
            deadlineAtEpochMs: watcher.deadlineAtEpochMs ?? startedAtEpochMs + timeoutMs,
            ...(args.executionResult.status === "in_progress"
              ? {}
              : {
                  completedAtEpochMs: watcherCompletedAtEpochMs,
                }),
            timeoutMs,
            pollIntervalMs,
            retryMax,
            attemptCount: watcher.attemptCount,
            ...(watcher.lastObservation ? { lastObservation: watcher.lastObservation } : {}),
            ...(watcher.assertions ? { lastAssertion: { assertions: watcher.assertions } } : {}),
            ...(args.executionResult.continuation
              ? { continuation: args.executionResult.continuation }
              : {}),
            artifactPathRel,
            ...(attempts.length > 0 ? { attempts } : {}),
          },
        });
        if (!persisted.ok) throw new Error(persisted.reasonCode);
      }
      if (
        activeWatcherContinuation &&
        typeof activeWatcherContinuation.watcherName === "string" &&
        typeof activeWatcherContinuation.dependencyStepOrder === "number" &&
        typeof activeWatcherContinuation.providerType === "string" &&
        typeof activeWatcherContinuation.deadlineAtEpochMs === "number" &&
        typeof activeWatcherContinuation.timeoutMs === "number" &&
        typeof activeWatcherContinuation.pollIntervalMs === "number" &&
        typeof activeWatcherContinuation.retryMax === "number"
      ) {
        const attempts = (activeWatcherContinuation.attempts ?? []).map((attempt) => ({
          attemptNumber: attempt.attempt,
          observedAtEpochMs: Date.parse(attempt.observedAt) || startedAtEpochMs,
          status: attempt.status,
          ...(attempt.reasonCode ? { reasonCode: attempt.reasonCode } : {}),
          ...(typeof attempt.durationMs === "number" ? { durationMs: attempt.durationMs } : {}),
        }));
        const persisted = upsertWatcherRun({
          store,
          projectName: project.projectName,
          projection: {
            planName: args.planRef.name,
            runId: args.runId,
            ...(args.suiteRunId ? { suiteRunId: args.suiteRunId } : {}),
            watcherName: activeWatcherContinuation.watcherName,
            dependencyStepOrder: activeWatcherContinuation.dependencyStepOrder,
            watcherIndex: activeWatcherContinuation.watcherIndex,
            providerType: activeWatcherContinuation.providerType,
            status: "in_progress",
            outcome: "blocked",
            startedAtEpochMs:
              Date.parse(activeWatcherContinuation.phaseStartedAt) || startedAtEpochMs,
            deadlineAtEpochMs: activeWatcherContinuation.deadlineAtEpochMs,
            timeoutMs: activeWatcherContinuation.timeoutMs,
            pollIntervalMs: activeWatcherContinuation.pollIntervalMs,
            retryMax: activeWatcherContinuation.retryMax,
            attemptCount: activeWatcherContinuation.attemptCount ?? attempts.length,
            ...(activeWatcherContinuation.nextAttemptAt &&
            Date.parse(activeWatcherContinuation.nextAttemptAt) > 0
              ? { nextAttemptAtEpochMs: Date.parse(activeWatcherContinuation.nextAttemptAt) }
              : {}),
            ...(activeWatcherContinuation.lastObservation
              ? { lastObservation: activeWatcherContinuation.lastObservation }
              : {}),
            ...(activeWatcherContinuation.lastAssertion
              ? { lastAssertion: activeWatcherContinuation.lastAssertion }
              : {}),
            continuation: activeWatcherContinuation,
            artifactPathRel,
            ...(attempts.length > 0 ? { attempts } : {}),
          },
        });
        if (!persisted.ok) throw new Error(persisted.reasonCode);
      }
    } finally {
      store.close();
    }
  }

  return {
    runDirAbs,
    contextResolvedPathAbs,
    executionResultPathAbs,
    evidencePathAbs,
    ...(writtenCorrelationPathAbs ? { correlationPathAbs: writtenCorrelationPathAbs } : {}),
    ...(persistenceWarnings.length > 0 ? { persistenceWarnings } : {}),
  };
}
