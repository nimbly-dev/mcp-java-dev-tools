import { promises as fs } from "node:fs";
import path from "node:path";
import { resolveRegressionPlansRootAbs } from "../../../spec/regression-execution-plan-spec/src/regression_artifact_paths.util";
import { renderWatcherResults } from "./regression_watcher_results_report";
import type {
  FailedAssertionReport,
  FailedAssertionRow,
  ProbeCoverageState,
  RenderArgs,
  RenderBlockedResult,
  RenderFromArtifactsArgs,
  RenderFromArtifactsResult,
  RenderResult,
  ReportColumn,
  ResolveRunDirArgs,
  StepRow,
} from "../models/regression_report.model";

const MAX_RENDERED_EXPECTED_LENGTH = 256;
const NOT_PERSISTED = "[not persisted]" as const;

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

function hasText(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function asCorrelationStatus(value: unknown): "ok" | "fail_closed" {
  if (value === "ok" || value === "matched") return "ok";
  return "fail_closed";
}

function toStepRecords(executionResult: Record<string, unknown>): Record<string, unknown>[] {
  if (Array.isArray(executionResult.steps)) {
    return executionResult.steps.filter((entry): entry is Record<string, unknown> =>
      isRecord(entry),
    );
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

function resolveProbeCoverage(
  step: Record<string, unknown>,
  evidence: Record<string, unknown>,
): ProbeCoverageState {
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

function resolveMemoryBytes(
  step: Record<string, unknown>,
  evidence: Record<string, unknown>,
): string {
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

function stableJson(value: unknown): string {
  if (value === null) return "null";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  if (Array.isArray(value)) return `[${value.map((entry) => stableJson(entry)).join(",")}]`;
  if (isRecord(value)) {
    return `{${Object.keys(value)
      .sort((left, right) => left.localeCompare(right))
      .map((key) => `${JSON.stringify(key)}:${stableJson(value[key])}`)
      .join(",")}}`;
  }
  return "[unrenderable]";
}

function formatExpected(value: unknown): string {
  if (value === "[REDACTED]") return "[REDACTED]";
  const normalized = escapeMarkdownTableCell(stableJson(value));
  return normalized.length > MAX_RENDERED_EXPECTED_LENGTH
    ? `${normalized.slice(0, MAX_RENDERED_EXPECTED_LENGTH - 3)}...`
    : normalized;
}

function escapeMarkdownTableCell(value: string): string {
  return value.replace(/\r\n|\r|\n/g, " ").replaceAll("|", "\\|");
}

function failedAssertionRows(steps: Record<string, unknown>[]): FailedAssertionRow[] {
  const rows: FailedAssertionRow[] = [];
  for (const [index, step] of steps.entries()) {
    const assertions = step.assertions;
    if (!Array.isArray(assertions)) continue;
    const order = asNumber(step.order) ?? index + 1;
    const endpoint = resolveEndpoint(step);
    for (const assertion of assertions) {
      if (!isRecord(assertion)) continue;
      const status = assertion.status;
      if (status !== "fail" && status !== "blocked_invalid") continue;
      rows.push({
        stepOrder: order,
        endpoint,
        assertionId: asString(assertion.id, "unknown_assertion"),
        actualPath: asString(assertion.actualPath, "n/a"),
        operator: asString(assertion.operator, "n/a"),
        status,
        expected:
          typeof assertion.expected === "undefined" ? "n/a" : formatExpected(assertion.expected),
        actual: NOT_PERSISTED,
        reasonCode: asString(assertion.reasonCode, "unknown"),
      });
    }
  }
  return rows.sort((left, right) => {
    if (left.stepOrder !== right.stepOrder) return left.stepOrder - right.stepOrder;
    const endpointOrder = left.endpoint.localeCompare(right.endpoint);
    return endpointOrder !== 0 ? endpointOrder : left.assertionId.localeCompare(right.assertionId);
  });
}

function formatFailedAssertionsTable(rows: FailedAssertionRow[]): string {
  const headers = [
    "Step",
    "Endpoint",
    "Assertion",
    "Actual Path",
    "Operator",
    "Status",
    "Expected",
    "Actual",
    "Reason",
  ];
  const line = (values: string[]) => `| ${values.join(" | ")} |`;
  return [
    line(headers),
    line(headers.map(() => "---")),
    ...rows.map((row) =>
      line([
        String(row.stepOrder),
        escapeMarkdownTableCell(row.endpoint),
        escapeMarkdownTableCell(row.assertionId),
        escapeMarkdownTableCell(row.actualPath),
        escapeMarkdownTableCell(row.operator),
        row.status,
        row.expected,
        row.actual,
        escapeMarkdownTableCell(row.reasonCode),
      ]),
    ),
  ].join("\n");
}

function renderFailedAssertions(
  steps: Record<string, unknown>[],
): FailedAssertionReport | undefined {
  const rows = failedAssertionRows(steps);
  return rows.length === 0 ? undefined : { rows, table: formatFailedAssertionsTable(rows) };
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
    .sort((a, b) =>
      a.order !== b.order ? a.order - b.order : a.endpoint.localeCompare(b.endpoint),
    );

  const columns: ReportColumn[] = [
    "endpoint",
    "status",
    "http_code",
    "duration_ms",
    "probe_coverage",
  ];
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
        ...(typeof args.correlation.keyType === "string"
          ? { keyType: args.correlation.keyType }
          : {}),
        ...(typeof args.correlation.keyValue === "string"
          ? { keyValue: args.correlation.keyValue }
          : {}),
        matchedEvents:
          typeof args.correlation.matchedEvents === "number" &&
          Number.isFinite(args.correlation.matchedEvents)
            ? args.correlation.matchedEvents
            : Array.isArray(args.correlation.timeline)
              ? args.correlation.timeline.length
              : 0,
        ...(typeof args.correlation.correlationSessionId === "string"
          ? { correlationSessionId: args.correlation.correlationSessionId }
          : {}),
      }
    : undefined;

  const failedAssertions = renderFailedAssertions(steps);
  return {
    columns,
    rows,
    table: formatTable(columns, rows),
    ...(failedAssertions ? { failedAssertions } : {}),
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

function blocked(
  reasonCode: RenderBlockedResult["reasonCode"],
  reason: string,
): RenderBlockedResult {
  return {
    status: "blocked",
    reasonCode,
    reason,
    nextAction: "Regenerate the regression run Artifact, then render the result again.",
  };
}

function validateExecutionResultForReport(
  value: unknown,
):
  | { ok: true; executionResult: Record<string, unknown> }
  | { ok: false; result: RenderBlockedResult } {
  if (!isRecord(value)) {
    return {
      ok: false,
      result: blocked(
        "run_result_execution_result_invalid",
        "execution.result.json must contain an object",
      ),
    };
  }
  if (!hasText(value.status)) {
    return {
      ok: false,
      result: blocked(
        "run_result_execution_status_missing",
        "execution.result.json is missing required field: status",
      ),
    };
  }
  if (!isRecord(value.preflight)) {
    return {
      ok: false,
      result: blocked(
        "run_result_preflight_missing",
        "execution.result.json is missing required object: preflight",
      ),
    };
  }
  const rawSteps = value.steps;
  if (!Array.isArray(rawSteps) && !isRecord(rawSteps)) {
    return {
      ok: false,
      result: blocked(
        "run_result_steps_missing",
        "execution.result.json is missing required steps array or object map",
      ),
    };
  }
  const expectedStepCount = Array.isArray(rawSteps)
    ? rawSteps.length
    : Object.keys(rawSteps).length;
  const steps = toStepRecords(value);
  if (steps.length !== expectedStepCount) {
    return {
      ok: false,
      result: blocked(
        "run_result_step_invalid",
        "execution.result.json has a non-object entry in steps",
      ),
    };
  }
  for (const [stepIndex, step] of steps.entries()) {
    if (!isRecord(step)) {
      return {
        ok: false,
        result: blocked(
          "run_result_step_invalid",
          `execution.result.json has invalid step: steps[${stepIndex}]`,
        ),
      };
    }
    if (!Number.isInteger(step.order) || !hasText(step.id) || !hasText(step.status)) {
      return {
        ok: false,
        result: blocked(
          "run_result_step_invalid",
          `execution.result.json has incomplete step: steps[${stepIndex}]`,
        ),
      };
    }
    if (typeof step.assertions === "undefined") continue;
    if (!Array.isArray(step.assertions)) {
      return {
        ok: false,
        result: blocked(
          "run_result_assertions_invalid",
          `execution.result.json has invalid assertions: steps[${stepIndex}].assertions`,
        ),
      };
    }
    for (const [assertionIndex, assertion] of step.assertions.entries()) {
      if (!isRecord(assertion)) {
        return {
          ok: false,
          result: blocked(
            "run_result_assertions_invalid",
            `execution.result.json has invalid assertion: steps[${stepIndex}].assertions[${assertionIndex}]`,
          ),
        };
      }
      for (const field of ["id", "actualPath", "operator", "status", "reasonCode"] as const) {
        if (!hasText(assertion[field])) {
          return {
            ok: false,
            result: blocked(
              "run_result_assertion_field_missing",
              `execution.result.json is missing required field: steps[${stepIndex}].assertions[${assertionIndex}].${field}`,
            ),
          };
        }
      }
      if (
        assertion.status !== "pass" &&
        assertion.status !== "fail" &&
        assertion.status !== "blocked_invalid" &&
        assertion.status !== "skipped_optional"
      ) {
        return {
          ok: false,
          result: blocked(
            "run_result_assertions_invalid",
            `execution.result.json has unsupported assertion status: steps[${stepIndex}].assertions[${assertionIndex}].status`,
          ),
        };
      }
    }
  }
  return { ok: true, executionResult: value };
}

async function readArtifactJson(
  filePath: string,
): Promise<{ ok: true; value: unknown } | { ok: false; result: RenderBlockedResult }> {
  let text: string;
  try {
    text = await fs.readFile(filePath, "utf8");
  } catch {
    return {
      ok: false,
      result: blocked(
        "run_result_artifact_missing",
        `required Artifact is missing: ${path.basename(filePath)}`,
      ),
    };
  }
  try {
    return { ok: true, value: JSON.parse(text) as unknown };
  } catch {
    return {
      ok: false,
      result: blocked(
        "run_result_artifact_invalid_json",
        `required Artifact contains invalid JSON: ${path.basename(filePath)}`,
      ),
    };
  }
}

export async function renderRegressionRunResultsTableFromArtifacts(
  args: RenderFromArtifactsArgs,
): Promise<RenderFromArtifactsResult> {
  const executionPath = path.join(args.runDirAbs, "execution.result.json");
  const evidencePath = path.join(args.runDirAbs, "evidence.json");
  const correlationPath = path.join(args.runDirAbs, "correlation", "correlation.json");
  const executionRead = await readArtifactJson(executionPath);
  if (!executionRead.ok) return executionRead.result;
  const executionValidation = validateExecutionResultForReport(executionRead.value);
  if (!executionValidation.ok) return executionValidation.result;
  const evidenceRead = await readArtifactJson(evidencePath);
  if (!evidenceRead.ok) return evidenceRead.result;
  if (!isRecord(evidenceRead.value)) {
    return blocked("run_result_evidence_invalid", "evidence.json must contain an object");
  }
  const executionResult = executionValidation.executionResult;
  const evidence = evidenceRead.value;
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
