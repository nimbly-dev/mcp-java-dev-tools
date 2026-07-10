type WatcherSummaryStatus = "not_configured" | "pass" | "fail" | "blocked";
type WatcherDetailStatus = "pass" | "fail_assertion" | "blocked_dependency" | "blocked_runtime";
type WatcherOutcome = "verified" | "failed_expectation" | "timed_out" | "blocked";

export type WatcherReportSummary = {
  triggerStatus: string;
  watcherStatus: WatcherSummaryStatus;
  watcherCount: number;
  verifiedCount: number;
  failedExpectationCount: number;
  timedOutCount: number;
  blockedCount: number;
};

export type WatcherReportDetailRow = {
  id: string;
  dependencyStepOrder: number;
  status: WatcherDetailStatus;
  outcome: WatcherOutcome;
  attemptCount: number;
  durationMs: number;
  timeoutMs: string;
  retryMax: string;
  reasonCode: string;
};

export type WatcherReportResult = {
  summary: WatcherReportSummary;
  rows: WatcherReportDetailRow[];
  table: string;
};

type WatcherReportColumn =
  | "id"
  | "dependency_step"
  | "status"
  | "outcome"
  | "attempt_count"
  | "duration_ms"
  | "timeout_ms"
  | "retry_max"
  | "reason_code";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asString(value: unknown, fallback = "n/a"): string {
  if (typeof value === "string" && value.trim().length > 0) return value;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return fallback;
}

function asFiniteNumber(value: unknown, fallback = 0): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function asWatcherSummaryStatus(value: unknown): WatcherSummaryStatus | undefined {
  if (value === "pass" || value === "fail" || value === "blocked" || value === "not_configured") {
    return value;
  }
  return undefined;
}

function asWatcherDetailStatus(value: unknown): WatcherDetailStatus {
  if (
    value === "pass" ||
    value === "fail_assertion" ||
    value === "blocked_dependency" ||
    value === "blocked_runtime"
  ) {
    return value;
  }
  return "blocked_runtime";
}

function asWatcherOutcome(value: unknown): WatcherOutcome {
  if (
    value === "verified" ||
    value === "failed_expectation" ||
    value === "timed_out" ||
    value === "blocked"
  ) {
    return value;
  }
  return "blocked";
}

function formatTable(columns: WatcherReportColumn[], rows: WatcherReportDetailRow[]): string {
  const headers = columns.map((column) => {
    if (column === "id") return "Watcher ID";
    if (column === "dependency_step") return "Dependency Step";
    if (column === "status") return "Status";
    if (column === "outcome") return "Outcome";
    if (column === "attempt_count") return "Attempts";
    if (column === "duration_ms") return "Duration (ms)";
    if (column === "timeout_ms") return "Timeout (ms)";
    if (column === "retry_max") return "Retry Max";
    return "Reason Code";
  });

  const lineFrom = (values: string[]) => `| ${values.join(" | ")} |`;
  const separator = lineFrom(headers.map(() => "---"));
  const body = rows.map((row) => {
    const values: string[] = [];
    for (const column of columns) {
      if (column === "id") values.push(row.id);
      else if (column === "dependency_step") values.push(String(row.dependencyStepOrder));
      else if (column === "status") values.push(row.status);
      else if (column === "outcome") values.push(row.outcome);
      else if (column === "attempt_count") values.push(String(row.attemptCount));
      else if (column === "duration_ms") values.push(String(row.durationMs));
      else if (column === "timeout_ms") values.push(row.timeoutMs);
      else if (column === "retry_max") values.push(row.retryMax);
      else values.push(row.reasonCode);
    }
    return lineFrom(values);
  });

  return [lineFrom(headers), separator, ...body].join("\n");
}

function toWatcherRecords(executionResult: Record<string, unknown>): Record<string, unknown>[] {
  if (!Array.isArray(executionResult.watchers)) {
    return [];
  }
  return executionResult.watchers.filter((entry): entry is Record<string, unknown> => isRecord(entry));
}

function deriveTriggerStatus(executionResult: Record<string, unknown>): string {
  const explicitTriggerStatus = asString(executionResult.triggerStatus, "");
  if (explicitTriggerStatus) {
    return explicitTriggerStatus;
  }

  const steps = Array.isArray(executionResult.steps)
    ? executionResult.steps.filter((entry): entry is Record<string, unknown> => isRecord(entry))
    : [];
  if (steps.some((step) => step.status === "blocked_runtime" || step.status === "blocked_dependency")) {
    return "blocked";
  }
  if (steps.some((step) => step.status === "fail_assertion" || step.status === "fail_http")) {
    return "fail";
  }
  if (steps.length > 0) {
    return "pass";
  }

  return "unknown";
}

function deriveWatcherStatus(
  watcherStatus: unknown,
  rows: WatcherReportDetailRow[],
): WatcherSummaryStatus {
  const explicitWatcherStatus = asWatcherSummaryStatus(watcherStatus);
  if (explicitWatcherStatus) {
    return explicitWatcherStatus;
  }
  if (rows.length === 0) {
    return "not_configured";
  }
  if (rows.some((row) => row.status === "blocked_runtime" || row.status === "blocked_dependency")) {
    return "blocked";
  }
  if (rows.some((row) => row.status === "fail_assertion")) {
    return "fail";
  }
  return "pass";
}

export function renderWatcherResults(args: {
  executionResult: Record<string, unknown>;
}): WatcherReportResult | undefined {
  const watcherRecords = toWatcherRecords(args.executionResult);
  if (watcherRecords.length === 0) {
    return undefined;
  }

  const rows = watcherRecords
    .map((watcher) => {
      const waitPolicy = isRecord(watcher.waitPolicy) ? watcher.waitPolicy : null;
      return {
        id: asString(watcher.id, "unknown_watcher"),
        dependencyStepOrder: asFiniteNumber(watcher.dependencyStepOrder),
        status: asWatcherDetailStatus(watcher.status),
        outcome: asWatcherOutcome(watcher.outcome),
        attemptCount: asFiniteNumber(watcher.attemptCount),
        durationMs: asFiniteNumber(watcher.durationMs),
        timeoutMs: waitPolicy ? asString(waitPolicy.timeoutMs, "n/a") : "n/a",
        retryMax: waitPolicy ? asString(waitPolicy.retryMax, "n/a") : "n/a",
        reasonCode: asString(watcher.reasonCode, "n/a"),
      };
    })
    .sort((lhs, rhs) => {
      if (lhs.dependencyStepOrder !== rhs.dependencyStepOrder) {
        return lhs.dependencyStepOrder - rhs.dependencyStepOrder;
      }
      return lhs.id.localeCompare(rhs.id);
    });

  const summary: WatcherReportSummary = {
    triggerStatus: deriveTriggerStatus(args.executionResult),
    watcherStatus: deriveWatcherStatus(args.executionResult.watcherStatus, rows),
    watcherCount: rows.length,
    verifiedCount: rows.filter((row) => row.outcome === "verified").length,
    failedExpectationCount: rows.filter((row) => row.outcome === "failed_expectation").length,
    timedOutCount: rows.filter((row) => row.outcome === "timed_out").length,
    blockedCount: rows.filter((row) => row.outcome === "blocked").length,
  };

  return {
    summary,
    rows,
    table: formatTable(
      [
        "id",
        "dependency_step",
        "status",
        "outcome",
        "attempt_count",
        "duration_ms",
        "timeout_ms",
        "retry_max",
        "reason_code",
      ],
      rows,
    ),
  };
}
