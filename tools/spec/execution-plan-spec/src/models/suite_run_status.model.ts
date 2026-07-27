export type SuiteRunStatus = "pass" | "fail" | "blocked" | "in_progress";

export type SuiteWatcherPhaseStatus =
  | "not_configured"
  | "pass"
  | "fail"
  | "blocked"
  | "in_progress";

export type SuiteExternalVerificationPhaseStatus =
  | "not_configured"
  | "pass"
  | "fail"
  | "blocked"
  | "in_progress"
  | "skipped_dependency";
