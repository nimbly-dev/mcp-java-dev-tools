import { promises as fs } from "node:fs";
import path from "node:path";
import { resolveRegressionPlansRootAbs } from "../../../spec/regression-execution-plan-spec/src/regression_artifact_paths.util";
import {
  renderWatcherResults,
  type WatcherReportDetailRow,
  type WatcherReportSummary,
} from "./regression_watcher_results_report";

type ReportColumn = "endpoint" | "status" | "http_code" | "duration_ms" | "probe_coverage" | "memory_bytes";
type ProbeCoverageState = "verified_line_hit" | "http_only_unverified_line" | "unknown" | "n/a";

type StepRow = {
  order: number;
  endpoint: string;
  status: string;
  httpCode: string;
  durationMs: string;
  probeCoverage: ProbeCoverageState;
  memoryBytes: string;
};

type RenderArgs = {
  executionResult: Record<string, unknown>;
  evidence: Record<string, unknown>;
  memoryMetricDefined: boolean;
  correlation?: Record<string, unknown>;
};

type RenderResult = {
  columns: ReportColumn[];
  rows: StepRow[];
  table: string;
  watchers?: {
    summary: WatcherReportSummary;
    rows: WatcherReportDetailRow[];
    table: string;
  };
  correlation?: {
    status: "ok" | "fail_closed";
    reasonCode: string;
    keyType?: string;
    keyValue?: string;
    matchedEvents: number;
    correlationSessionId?: string;
  };
};

type RenderFromArtifactsArgs = {
  runDirAbs: string;
  memoryMetricDefined: boolean;
};

type ResolveRunDirArgs = {
  workspaceRootAbs: string;
  projectName?: string;
  planName?: string;
  runId?: string;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asString(value: unknown, fallback = "n/a"): string {
  if (typeof value === "string" && value.trim().length > 0) return value;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return fallback;
}

function asNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  return null;
}

function asCorrelationStatus(value: unknown): "ok" | "fail_closed" {
  if (value === "ok" || value === "matched") return "ok";
  return "fail_closed";
}

function toStepRecords(executionResult: Record<string, unknown>): Record<string, unknown>[] {
  if (Array.isArray(executionResult.steps)) {
    return executionResult.steps.filter((entry): entry is Record<string, unknown> => isRecord(entry));
  }
  if (isRecord(executionResult.steps)) {
    return Object.entries(executionResult.steps)
      .filter((entry): entry is [string, Record<string, unknown>] => isRecord(entry[1]))
      .map(([id, step], index) => {
        const withId = { id, ...step } as Record<string, unknown>;
        if (typeof withId.order === "undefined") {
          withId.order = index + 1;
        }
        return withId;
      });
  }
  return [];
}

function resolveEndpoint(step: Record<string, unknown>): string {
  const method = asString(step.httpMethod, asString(step.method, ""));
  const pathValue = asString(step.path, asString(step.pathTemplate, ""));
  if (method && pathValue) return `${method.toUpperCase()} ${pathValue}`;
  if (pathValue) return pathValue;
  return asString(step.id, "unknown_step");
}

function resolveProbeCoverage(step: Record<string, unknown>, evidence: Record<string, unknown>): ProbeCoverageState {
  const normalizeProbeCoverage = (value: string): ProbeCoverageState => {
    const normalized = value.trim().toLowerCase();
    if (normalized.length === 0) return "unknown";
    if (normalized === "verified_line_hit") return "verified_line_hit";
    if (normalized === "http_only_unverified_line") return "http_only_unverified_line";
    if (normalized === "n/a") return "n/a";
    return "unknown";
  };

  const stepCoverage = asString(step.probeCoverage, "");
  if (stepCoverage) return normalizeProbeCoverage(stepCoverage);

  const order = asNumber(step.order);
  const probe = evidence.probe;
  if (isRecord(probe)) {
    const byStep = probe.byStep;
    if (isRecord(byStep) && order !== null) {
      const perStep = byStep[String(order)];
      const byStepCoverage = asString(perStep, "");
      if (byStepCoverage) return normalizeProbeCoverage(byStepCoverage);
    }
    const probeStatus = asString(probe.status, "");
    if (probeStatus) return normalizeProbeCoverage(probeStatus);
  }
  return "unknown";
}

function resolveMemoryBytes(step: Record<string, unknown>, evidence: Record<string, unknown>): string {
  const stepMemory = asNumber(step.memoryBytes);
  if (stepMemory !== null) return String(stepMemory);

  const metrics = evidence.metrics;
  const order = asNumber(step.order);
  if (!isRecord(metrics) || order === null) return "n/a";
  const byStep = metrics.byStep;
  if (!isRecord(byStep)) return "n/a";
  const metricEntry = byStep[String(order)];
  if (!isRecord(metricEntry)) return "n/a";
  const metricMemory = asNumber(metricEntry.memoryBytes);
  return metricMemory === null ? "n/a" : String(metricMemory);
}

function formatTable(columns: ReportColumn[], rows: StepRow[]): string {
  const headers = columns.map((column) => {
    if (column === "endpoint") return "Endpoint";
    if (column === "status") return "Status";
    if (column === "http_code") return "HTTP Code";
    if (column === "duration_ms") return "Duration (ms)";
    if (column === "probe_coverage") return "Probe Coverage";
    return "Memory (bytes)";
  });

  const lineFrom = (values: string[]) => `| ${values.join(" | ")} |`;
  const separator = lineFrom(headers.map(() => "---"));
  const body = rows.map((row) => {
    const values: string[] = [];
    for (const column of columns) {
      if (column === "endpoint") values.push(row.endpoint);
      else if (column === "status") values.push(row.status);
      else if (column === "http_code") values.push(row.httpCode);
      else if (column === "duration_ms") values.push(row.durationMs);
      else if (column === "probe_coverage") values.push(row.probeCoverage);
      else values.push(row.memoryBytes);
    }
    return lineFrom(values);
  });

  return [lineFrom(headers), separator, ...body].join("\n");
}

export function renderRegressionRunResultsTable(args: RenderArgs): RenderResult {
  const steps = toStepRecords(args.executionResult);
  const evidence = isRecord(args.evidence) ? args.evidence : {};
  const watcherReport = renderWatcherResults({
    executionResult: args.executionResult,
  });

  const rows: StepRow[] = steps
    .map((step, index) => {
      const order = asNumber(step.order);
      const status = asString(step.status, "unknown");
      const httpCode = asString(step.httpStatus, asString(step.statusCode, "n/a"));
      const durationMs = asString(step.durationMs, "n/a");
      return {
        order: order === null ? index + 1 : order,
        endpoint: resolveEndpoint(step),
        status,
        httpCode,
        durationMs,
        probeCoverage: resolveProbeCoverage(step, evidence),
        memoryBytes: resolveMemoryBytes(step, evidence),
      };
    })
    .sort((a, b) => (a.order !== b.order ? a.order - b.order : a.endpoint.localeCompare(b.endpoint)));

  const columns: ReportColumn[] = ["endpoint", "status", "http_code", "duration_ms", "probe_coverage"];
  if (args.memoryMetricDefined) {
    columns.push("memory_bytes");
  }

  if (rows.length === 0) {
    rows.push({
      order: 0,
      endpoint: "(no executed endpoints)",
      status: asString(args.executionResult.status, "blocked"),
      httpCode: "n/a",
      durationMs: "n/a",
      probeCoverage: "n/a",
      memoryBytes: "n/a",
    });
  }

  const correlation: RenderResult["correlation"] | undefined = isRecord(args.correlation)
    ? {
        status: asCorrelationStatus(args.correlation.status),
        reasonCode: asString(args.correlation.reasonCode, "insufficient_evidence"),
        ...(typeof args.correlation.keyType === "string" ? { keyType: args.correlation.keyType } : {}),
        ...(typeof args.correlation.keyValue === "string" ? { keyValue: args.correlation.keyValue } : {}),
        matchedEvents: typeof args.correlation.matchedEvents === "number" && Number.isFinite(args.correlation.matchedEvents)
          ? args.correlation.matchedEvents
          : Array.isArray(args.correlation.timeline)
            ? args.correlation.timeline.length
            : 0,
        ...(typeof args.correlation.correlationSessionId === "string"
          ? { correlationSessionId: args.correlation.correlationSessionId }
          : {}),
      }
    : undefined;

  return {
    columns,
    rows,
    table: formatTable(columns, rows),
    ...(watcherReport
      ? {
          watchers: {
            summary: watcherReport.summary,
            rows: watcherReport.rows,
            table: watcherReport.table,
          },
        }
      : {}),
    ...(correlation ? { correlation } : {}),
  };
}

export async function renderRegressionRunResultsTableFromArtifacts(
  args: RenderFromArtifactsArgs,
): Promise<RenderResult> {
  const executionPath = path.join(args.runDirAbs, "execution.result.json");
  const evidencePath = path.join(args.runDirAbs, "evidence.json");
  const correlationPath = path.join(args.runDirAbs, "correlation", "correlation.json");
  const [executionText, evidenceText] = await Promise.all([
    fs.readFile(executionPath, "utf8"),
    fs.readFile(evidencePath, "utf8"),
  ]);
  const executionResult = JSON.parse(executionText) as Record<string, unknown>;
  const evidence = JSON.parse(evidenceText) as Record<string, unknown>;
  let correlation: Record<string, unknown> | undefined;
  try {
    const correlationText = await fs.readFile(correlationPath, "utf8");
    const parsed = JSON.parse(correlationText) as unknown;
    if (isRecord(parsed)) correlation = parsed;
  } catch {
    correlation = undefined;
  }
  return renderRegressionRunResultsTable({
    executionResult,
    evidence,
    memoryMetricDefined: args.memoryMetricDefined,
    ...(correlation ? { correlation } : {}),
  });
}

function newestName(names: string[]): string | null {
  if (names.length === 0) return null;
  return [...names].sort((a, b) => b.localeCompare(a))[0] ?? null;
}

async function existingDirChildren(parentAbs: string): Promise<string[]> {
  try {
    const entries = await fs.readdir(parentAbs, { withFileTypes: true });
    return entries.filter((entry) => entry.isDirectory()).map((entry) => entry.name);
  } catch {
    return [];
  }
}

async function dirExists(dirAbs: string): Promise<boolean> {
  try {
    const stats = await fs.stat(dirAbs);
    return stats.isDirectory();
  } catch {
    return false;
  }
}

export async function resolveRegressionRunDirAbs(args: ResolveRunDirArgs): Promise<string | null> {
  const plansRootAbs = await resolveRegressionPlansRootAbs(args.workspaceRootAbs, args.projectName);
  const planRunsRoot =
    typeof args.planName === "string" && args.planName.trim().length > 0
      ? path.join(plansRootAbs, args.planName, "runs")
      : null;

  if (!planRunsRoot) {
    return null;
  }

  if (typeof args.runId === "string" && args.runId.trim().length > 0) {
    const planRunAbs = path.join(planRunsRoot, args.runId);
    if (await dirExists(planRunAbs)) return planRunAbs;
    return null;
  }

  const planRunNames = await existingDirChildren(planRunsRoot);
  const latestPlanRun = newestName(planRunNames);
  return latestPlanRun ? path.join(planRunsRoot, latestPlanRun) : null;
}
