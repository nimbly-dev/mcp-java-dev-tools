import { promises as fs } from "node:fs";
import { readdirSync, statSync } from "node:fs";
import path from "node:path";

import type {
  CorrelationIndexRebuildResult,
  CorrelationArtifact,
  RegressionRunArtifactsWriteResult,
  WriteRegressionRunArtifactsInput,
} from "@tools-regression-execution-plan-spec/models/regression_run_artifact.model";
import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";
import { correlateEvents } from "@tools-regression-execution-plan-spec/regression_correlation.util";

export type {
  CorrelationIndexRebuildResult,
  RegressionPlanReference,
  RegressionRunArtifactsWriteResult,
  RegressionRunExecutionResult,
  RegressionRunStatus,
  WriteRegressionRunArtifactsInput,
} from "@tools-regression-execution-plan-spec/models/regression_run_artifact.model";

const RUN_ID_PATTERN = /^(?:\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}Z_\d{2}|\d{10,})$/;
const SECRET_KEY_PATTERN = /(?:token|secret|password|authorization|api[-_]?key|bearer)/i;
const SECRET_VALUE_PATTERN =
  /(?:\bbearer\s+[a-z0-9\-._~+/]+=*|\bghp_[a-z0-9]+|\bsk-[a-z0-9]{12,}|\bapi[_-]?key\b|\bpassword\b)/i;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function stripRedundantResolvedContextFields(
  input: Record<string, unknown>,
): Record<string, unknown> {
  const next = { ...input };
  delete next.scope;
  return next;
}

function sanitizeByKey(
  value: unknown,
  explicitSecretPaths: Set<string>,
  parentPath: string | null = null,
): unknown {
  if (typeof value === "string" && SECRET_VALUE_PATTERN.test(value)) {
    return "[REDACTED]";
  }
  if (Array.isArray(value)) {
    return value.map((item) => sanitizeByKey(item, explicitSecretPaths, parentPath));
  }
  if (!isRecord(value)) {
    return value;
  }
  const output: Record<string, unknown> = {};
  for (const [key, child] of Object.entries(value)) {
    const currentPath = parentPath ? `${parentPath}.${key}` : key;
    const isExplicitSecret = explicitSecretPaths.has(key) || explicitSecretPaths.has(currentPath);
    const isPatternSecret = SECRET_KEY_PATTERN.test(key);
    if (isExplicitSecret || isPatternSecret) {
      continue;
    }
    output[key] = sanitizeByKey(child, explicitSecretPaths, currentPath);
  }
  return output;
}

function normalizeDiscoveryEvidence(discovery: unknown): unknown {
  if (!isRecord(discovery)) return undefined;
  const attempted = discovery.attempted === true;
  const status = discovery.status === "blocked" ? "blocked" : "resolved";
  const reasonCode = typeof discovery.reasonCode === "string" ? discovery.reasonCode : "ok";
  const rawOutcomes = Array.isArray(discovery.outcomes) ? discovery.outcomes : [];

  const outcomes = rawOutcomes
    .filter((entry) => isRecord(entry))
    .map((entry) => {
      const output: Record<string, unknown> = {
        key: String(entry.key ?? ""),
        source: entry.source === "runtime_context" ? "runtime_context" : "datasource",
        outcome: String(entry.outcome ?? "blocked_runtime_error"),
        reasonCode: String(entry.reasonCode ?? "discovery_adapter_failure"),
      };
      if (typeof entry.candidateCount === "number") output.candidateCount = entry.candidateCount;
      if (typeof entry.sourceRef === "string") output.sourceRef = entry.sourceRef;
      return output;
    })
    .sort((a, b) => {
      const lhs = `${String(a.key)}:${String(a.source)}`;
      const rhs = `${String(b.key)}:${String(b.source)}`;
      return lhs.localeCompare(rhs);
    });

  return {
    attempted,
    status,
    reasonCode,
    outcomes,
  };
}

function normalizeEvidencePayload(evidence: Record<string, unknown>): Record<string, unknown> {
  if (!("discovery" in evidence)) return evidence;
  const normalized = { ...evidence };
  const discovery = normalizeDiscoveryEvidence(evidence.discovery);
  if (typeof discovery === "undefined") {
    delete normalized.discovery;
  } else {
    normalized.discovery = discovery;
  }
  return normalized;
}

function normalizeCorrelationPayload(correlation: CorrelationArtifact): Record<string, unknown> {
  const timeline = [...(correlation.timeline ?? [])].sort((a, b) => {
    if (a.timestampEpochMs !== b.timestampEpochMs) return a.timestampEpochMs - b.timestampEpochMs;
    return `${a.probeId}:${a.eventId}`.localeCompare(`${b.probeId}:${b.eventId}`);
  });

  return {
    status: correlation.status,
    reasonCode: correlation.reasonCode,
    ...(typeof correlation.correlationSessionId === "string"
      ? { correlationSessionId: correlation.correlationSessionId }
      : {}),
    keyType: correlation.keyType,
    ...(typeof correlation.keyValue === "string" ? { keyValue: correlation.keyValue } : {}),
    window: correlation.window,
    ...(Array.isArray(correlation.expectedFlow) ? { expectedFlow: correlation.expectedFlow } : {}),
    timeline,
    ...(Array.isArray(correlation.evidenceRefs) ? { evidenceRefs: correlation.evidenceRefs } : {}),
    ...(typeof correlation.generatedAtEpochMs === "number"
      ? { generatedAtEpochMs: correlation.generatedAtEpochMs }
      : {}),
  };
}

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
  const keyValue = typeof keyValueRaw === "string" && keyValueRaw.trim().length > 0 ? keyValueRaw : keyFromContext;

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
      };
    })
    .filter((event) => event.eventId && event.probeId && Number.isFinite(event.timestampEpochMs));

  if (typeof keyValue !== "string" || keyValue.trim().length === 0) {
    return {
      status: "fail_closed",
      reasonCode: "missing_correlation_key",
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
      ...(typeof policyRaw.startEpochMs === "number" ? { startEpochMs: policyRaw.startEpochMs } : {}),
      ...(typeof policyRaw.endEpochMs === "number" ? { endEpochMs: policyRaw.endEpochMs } : {}),
      maxWindowMs,
    },
    ...(Array.isArray(expectedFlow) ? { expectedFlow } : {}),
    timeline,
    generatedAtEpochMs: args.now.getTime(),
  };
}

type CorrelationIndexEntry = {
  runId: string;
  planName: string;
  runPath: string;
  generatedAtEpochMs: number;
  status: "ok" | "fail_closed";
  reasonCode: string;
  keyType: "traceId" | "requestId" | "messageId";
  keyValue?: string;
  correlationSessionId?: string;
  window: {
    startEpochMs?: number;
    endEpochMs?: number;
    maxWindowMs: number;
  };
  probeIds: string[];
};

function asCorrelationVerdict(value: unknown): "ok" | "fail_closed" {
  return value === "ok" || value === "matched" ? "ok" : "fail_closed";
}

function asCorrelationReasonCode(value: unknown): string {
  return typeof value === "string" && value.trim().length > 0 ? value : "insufficient_evidence";
}

function normalizeCanonicalIndexEntry(typed: Record<string, unknown>): CorrelationIndexEntry | null {
  const runId = typeof typed.runId === "string" ? typed.runId : "";
  const planName = typeof typed.planName === "string" ? typed.planName : "";
  const runPath = typeof typed.runPath === "string" ? typed.runPath : "";
  if (!runId || !planName || !runPath) return null;
  return {
    runId,
    planName,
    runPath,
    generatedAtEpochMs: typeof typed.generatedAtEpochMs === "number" ? typed.generatedAtEpochMs : 0,
    status: asCorrelationVerdict(typed.status),
    reasonCode: asCorrelationReasonCode(typed.reasonCode),
    keyType: asCorrelationKeyType(typed.keyType),
    ...(typeof typed.keyValue === "string" ? { keyValue: typed.keyValue } : {}),
    ...(typeof typed.correlationSessionId === "string" ? { correlationSessionId: typed.correlationSessionId } : {}),
    window: isRecord(typed.window) ? normalizeWindowRecord(typed.window) : { maxWindowMs: 0 },
    probeIds: Array.isArray(typed.probeIds) ? typed.probeIds.map((v) => String(v)) : [],
  };
}

function toIndexEntryFromRunArtifact(args: {
  workspaceRootAbs: string;
  planName: string;
  runId: string;
  runDirAbs: string;
  correlation: CorrelationArtifact;
  now: Date;
}): CorrelationIndexEntry {
  return {
    runId: args.runId,
    planName: args.planName,
    runPath: path.relative(args.workspaceRootAbs, args.runDirAbs).replaceAll("\\", "/"),
    generatedAtEpochMs: args.correlation.generatedAtEpochMs ?? args.now.getTime(),
    status: args.correlation.status,
    reasonCode: args.correlation.reasonCode,
    keyType: args.correlation.keyType,
    ...(typeof args.correlation.keyValue === "string" ? { keyValue: args.correlation.keyValue } : {}),
    ...(typeof args.correlation.correlationSessionId === "string"
      ? { correlationSessionId: args.correlation.correlationSessionId }
      : {}),
    window: args.correlation.window,
    probeIds: Array.from(new Set(args.correlation.timeline.map((event) => event.probeId))).sort(),
  };
}

function correlationFileToIndexEntry(args: {
  workspaceRootAbs: string;
  runDirAbs: string;
  correlation: Record<string, unknown>;
  now: Date;
}): CorrelationIndexEntry | null {
  const relativeRun = path.relative(args.workspaceRootAbs, args.runDirAbs).replaceAll("\\", "/");
  const match = relativeRun.match(/^\.mcpjvm\/[^/]+\/plans\/regression\/([^/]+)\/runs\/([^/]+)$/);
  if (!match) return null;
  const planName = match[1];
  const runId = match[2];
  if (!planName || !runId) return null;
  return {
    runId,
    planName,
    runPath: relativeRun,
    generatedAtEpochMs:
      typeof args.correlation.generatedAtEpochMs === "number"
        ? args.correlation.generatedAtEpochMs
        : args.now.getTime(),
    status: asCorrelationVerdict(args.correlation.status),
    reasonCode: asCorrelationReasonCode(args.correlation.reasonCode),
    keyType: asCorrelationKeyType(args.correlation.keyType),
    ...(typeof args.correlation.keyValue === "string" ? { keyValue: args.correlation.keyValue } : {}),
    ...(typeof args.correlation.correlationSessionId === "string"
      ? { correlationSessionId: args.correlation.correlationSessionId }
      : {}),
    window: isRecord(args.correlation.window) ? normalizeWindowRecord(args.correlation.window) : { maxWindowMs: 0 },
    probeIds: Array.isArray(args.correlation.timeline)
      ? Array.from(
          new Set(
            args.correlation.timeline
              .filter((event) => isRecord(event) && typeof event.probeId === "string")
              .map((event) => String((event as Record<string, unknown>).probeId)),
          ),
        ).sort()
      : [],
  };
}

function normalizeWindowRecord(input: Record<string, unknown>): {
  startEpochMs?: number;
  endEpochMs?: number;
  maxWindowMs: number;
} {
  return {
    ...(typeof input.startEpochMs === "number" ? { startEpochMs: input.startEpochMs } : {}),
    ...(typeof input.endEpochMs === "number" ? { endEpochMs: input.endEpochMs } : {}),
    maxWindowMs: Number(input.maxWindowMs ?? 0),
  };
}

async function updateCorrelationIndex(args: {
  workspaceRootAbs: string;
  correlation: CorrelationArtifact;
  runId: string;
  planName: string;
  runDirAbs: string;
  now: Date;
}): Promise<string> {
  const indexPathAbs = path.join(args.workspaceRootAbs, ".mcpjvm", "correlation-index.json");
  let entries: CorrelationIndexEntry[] = [];
  try {
    const current = JSON.parse(await fs.readFile(indexPathAbs, "utf8")) as { entries?: unknown };
    if (Array.isArray(current.entries)) {
      entries = current.entries
        .filter((item) => isRecord(item))
        .map((item) => normalizeCanonicalIndexEntry(item as Record<string, unknown>))
        .filter((entry): entry is CorrelationIndexEntry => entry !== null);
    }
  } catch {
    entries = [];
  }

  const nextEntry = toIndexEntryFromRunArtifact({
    workspaceRootAbs: args.workspaceRootAbs,
    planName: args.planName,
    runId: args.runId,
    runDirAbs: args.runDirAbs,
    correlation: args.correlation,
    now: args.now,
  });

  const filtered = entries.filter((entry) => !(entry.planName === args.planName && entry.runId === args.runId));
  const withNext = filtered.concat(nextEntry);
  const existingEntries: CorrelationIndexEntry[] = [];
  for (const entry of withNext) {
    const runAbs = path.join(args.workspaceRootAbs, entry.runPath);
    try {
      const stat = await fs.stat(runAbs);
      if (stat.isDirectory()) existingEntries.push(entry);
    } catch {
      // prune stale index entry
    }
  }
  existingEntries.sort((a, b) => {
    if (a.generatedAtEpochMs !== b.generatedAtEpochMs) return a.generatedAtEpochMs - b.generatedAtEpochMs;
    return `${a.planName}:${a.runId}`.localeCompare(`${b.planName}:${b.runId}`);
  });

  await fs.mkdir(path.dirname(indexPathAbs), { recursive: true });
  await writeJsonFile(indexPathAbs, {
    version: 1,
    generatedAt: args.now.toISOString(),
    entries: existingEntries,
  });
  return indexPathAbs;
}

export async function rebuildCorrelationIndex(args: {
  workspaceRootAbs: string;
  now?: Date;
}): Promise<CorrelationIndexRebuildResult> {
  const now = args.now ?? new Date();
  const root = await resolveRegressionPlansRootAbs(args.workspaceRootAbs);
  const entries: CorrelationIndexEntry[] = [];
  try {
    const plans = await fs.readdir(root, { withFileTypes: true });
    for (const planDir of plans) {
      if (!planDir.isDirectory()) continue;
      const runsRoot = path.join(root, planDir.name, "runs");
      let runDirs: import("node:fs").Dirent[] = [];
      try {
        runDirs = await fs.readdir(runsRoot, { withFileTypes: true });
      } catch {
        continue;
      }
      for (const runDir of runDirs) {
        if (!runDir.isDirectory()) continue;
        const runAbs = path.join(runsRoot, runDir.name);
        const corrPath = path.join(runAbs, "correlation.json");
        let parsed: unknown;
        try {
          parsed = JSON.parse(await fs.readFile(corrPath, "utf8"));
        } catch {
          continue;
        }
        if (!isRecord(parsed)) continue;
        const entry = correlationFileToIndexEntry({
          workspaceRootAbs: args.workspaceRootAbs,
          runDirAbs: runAbs,
          correlation: parsed,
          now,
        });
        if (entry) entries.push(entry);
      }
    }
  } catch {
    // no regression folder yet
  }

  entries.sort((a, b) => {
    if (a.generatedAtEpochMs !== b.generatedAtEpochMs) return a.generatedAtEpochMs - b.generatedAtEpochMs;
    return `${a.planName}:${a.runId}`.localeCompare(`${b.planName}:${b.runId}`);
  });

  const indexPathAbs = path.join(args.workspaceRootAbs, ".mcpjvm", "correlation-index.json");
  await fs.mkdir(path.dirname(indexPathAbs), { recursive: true });
  await writeJsonFile(indexPathAbs, {
    version: 1,
    generatedAt: now.toISOString(),
    entries,
  });
  return { indexPathAbs, entriesCount: entries.length };
}

async function writeJsonFile(filePathAbs: string, payload: Record<string, unknown>): Promise<void> {
  await fs.writeFile(filePathAbs, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
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

export function buildRunArtifactDirAbs(workspaceRootAbs: string, planName: string, runId: string): string {
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
    if (error instanceof Error && (error.message === "project_artifact_missing" || error.message === "project_artifact_ambiguous")) {
      throw error;
    }
    throw new Error("project_artifact_missing");
  }
  return path.join(workspaceRootAbs, ".mcpjvm", String(projectName), "plans", "regression", safePlanName, "runs", runId);
}

export async function writeRegressionRunArtifacts(
  args: WriteRegressionRunArtifactsInput,
): Promise<RegressionRunArtifactsWriteResult> {
  if (!args.planRef?.name) {
    throw new Error("plan_name_missing");
  }
  const plansRootAbs = await resolveRegressionPlansRootAbs(args.workspaceRootAbs);
  const runDirAbs = path.join(plansRootAbs, normalizePlanName(args.planRef.name), "runs", args.runId);
  if (!RUN_ID_PATTERN.test(args.runId)) {
    throw new Error("run_id_invalid");
  }
  await fs.mkdir(runDirAbs, { recursive: true });

  const explicitSecretPaths = new Set(args.secretContextKeys ?? []);
  const now = args.now ?? new Date();

  const contextResolvedPathAbs = path.join(runDirAbs, "context.resolved.json");
  const executionResultPathAbs = path.join(runDirAbs, "execution.result.json");
  const evidencePathAbs = path.join(runDirAbs, "evidence.json");
  const correlationPathAbs = path.join(runDirAbs, "correlation.json");

  const contextResolvedPayload = sanitizeByKey(
    {
      resolvedAt: now.toISOString(),
      ...(args.planRef ? { planRef: args.planRef } : {}),
      ...stripRedundantResolvedContextFields(args.resolvedContext),
    },
    explicitSecretPaths,
  ) as Record<string, unknown>;

  const executionResultPayload = sanitizeByKey(
    {
      ...args.executionResult,
      ...(args.planRef ? { planRef: args.planRef } : {}),
      runId: args.runId,
    },
    explicitSecretPaths,
  ) as Record<string, unknown>;

  const evidencePayload = sanitizeByKey(
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

  let writtenCorrelationPathAbs: string | undefined;
  let writtenCorrelationIndexPathAbs: string | undefined;
  const correlation = args.correlation
    ? args.correlation
    : toCorrelationArtifactFromEvidence({
        evidence: args.evidence,
        resolvedContext: args.resolvedContext,
        now,
      });
  if (correlation) {
    const correlationPayload = sanitizeByKey(
      normalizeCorrelationPayload(correlation),
      explicitSecretPaths,
    ) as Record<string, unknown>;
    await writeJsonFile(correlationPathAbs, correlationPayload);
    writtenCorrelationPathAbs = correlationPathAbs;
    writtenCorrelationIndexPathAbs = await updateCorrelationIndex({
      workspaceRootAbs: args.workspaceRootAbs,
      correlation,
      runId: args.runId,
      planName: args.planRef.name,
      runDirAbs,
      now,
    });
  }

  return {
    runDirAbs,
    contextResolvedPathAbs,
    executionResultPathAbs,
    evidencePathAbs,
    ...(writtenCorrelationPathAbs ? { correlationPathAbs: writtenCorrelationPathAbs } : {}),
    ...(writtenCorrelationIndexPathAbs ? { correlationIndexPathAbs: writtenCorrelationIndexPathAbs } : {}),
  };
}

