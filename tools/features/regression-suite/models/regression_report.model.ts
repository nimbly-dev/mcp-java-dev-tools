export type ReportColumn =
  "endpoint" | "status" | "http_code" | "duration_ms" | "probe_coverage" | "memory_bytes";
export type ProbeCoverageState =
  "verified_line_hit" | "http_only_unverified_line" | "unknown" | "n/a";
export type StepRow = {
  order: number;
  endpoint: string;
  status: string;
  httpCode: string;
  durationMs: string;
  probeCoverage: ProbeCoverageState;
  memoryBytes: string;
};
export type RenderArgs = {
  executionResult: Record<string, unknown>;
  evidence: Record<string, unknown>;
  memoryMetricDefined: boolean;
  correlation?: Record<string, unknown>;
};
export type RenderResult = {
  columns: ReportColumn[];
  rows: StepRow[];
  table: string;
  watchers?: { summary: WatcherReportSummary; rows: WatcherReportDetailRow[]; table: string };
  correlation?: {
    status: "ok" | "fail_closed";
    reasonCode: string;
    keyType?: string;
    keyValue?: string;
    matchedEvents: number;
    correlationSessionId?: string;
  };
};
export type RenderFromArtifactsArgs = { runDirAbs: string; memoryMetricDefined: boolean };
export type ResolveRunDirArgs = {
  workspaceRootAbs: string;
  projectName?: string;
  planName?: string;
  runId?: string;
};
export type WatcherSummaryStatus = "not_configured" | "pass" | "fail" | "blocked";
export type WatcherDetailStatus =
  "pass" | "fail_assertion" | "blocked_dependency" | "blocked_runtime";
export type WatcherOutcome = "verified" | "failed_expectation" | "timed_out" | "blocked";
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
export type WatcherReportColumn =
  | "id"
  | "dependency_step"
  | "status"
  | "outcome"
  | "attempt_count"
  | "duration_ms"
  | "timeout_ms"
  | "retry_max"
  | "reason_code";
