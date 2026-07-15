export type RunStateDatabase = {
  exec(sql: string): void;
  prepare(sql: string): {
    get(...parameters: unknown[]): Record<string, unknown> | undefined;
    all(...parameters: unknown[]): Array<Record<string, unknown>>;
    run(...parameters: unknown[]): void;
  };
  close(): void;
};

export type RunStateStoreFailureCode =
  | "state_store_open_failed"
  | "state_store_locked"
  | "state_store_corrupt"
  | "state_store_schema_unsupported"
  | "state_store_migration_failed"
  | "state_store_project_mismatch"
  | "state_store_path_invalid"
  | "state_store_cutover_not_ready"
  | "state_store_cutover_conflict"
  | "state_store_cutover_failed"
  | "legacy_backfill_required"
  | "legacy_write_disabled"
  | "state_store_required_after_cutover";
export type RunStateRebuildFailureCode =
  | "state_store_rebuild_active_runs"
  | "state_store_rebuild_source_invalid"
  | "state_store_rebuild_conflict"
  | "state_store_rebuild_integrity_failed"
  | "state_store_rebuild_replace_failed"
  | "state_store_rebuild_non_reconstructible"
  | "state_store_rebuild_failed";
export type RunStateRebuildFailure = {
  ok: false;
  reasonCode: RunStateRebuildFailureCode;
  reason: string;
  nextAction: "retry_state_store" | "correct_state_store_input" | "rebuild_state_store";
  reasonMeta?: Record<string, unknown>;
};
export type RunStateRebuildSummary = {
  scannedRuns: number;
  rebuiltRuns: number;
  skippedRuns: number;
  invalidRuns: number;
  conflictingRuns: number;
  rebuiltCorrelations: number;
  rebuiltWatchers: number;
  rebuiltExternalVerifications: number;
  nonReconstructibleActiveStates: number;
  reasonsTruncated?: boolean;
  reasons?: Array<Record<string, unknown>>;
};
export type RunStateRebuildResult =
  | {
      ok: true;
      summary: RunStateRebuildSummary;
      databasePathAbs: string;
      quarantinePathAbs?: string;
    }
  | RunStateRebuildFailure;
export type LegacyBackfillSummary = {
  scannedEntries: number;
  insertedEntries: number;
  skippedEntries: number;
  conflictingEntries: number;
  invalidEntries: number;
  nonReconstructibleEntries: number;
  sourcePathRel: string;
  sourceChecksum: string;
  detectedLegacySchemaVersion: number;
  backfillStatus: "completed" | "noop" | "rejected" | "failed";
  nextAction: "none" | "run_state_store_rebuild" | "correct_legacy_source" | "retry_state_store";
  reasonsTruncated?: boolean;
  reasons?: Array<Record<string, unknown>>;
};
export type LegacyBackfillFailure = {
  ok: false;
  reasonCode:
    | "legacy_backfill_source_missing"
    | "legacy_backfill_source_invalid"
    | "legacy_backfill_schema_unsupported"
    | "legacy_backfill_target_not_empty"
    | "legacy_backfill_conflict"
    | "legacy_backfill_checksum_changed"
    | "legacy_backfill_failed"
    | "legacy_write_disabled";
  reason: string;
  nextAction:
    | "correct_legacy_source"
    | "run_state_store_rebuild"
    | "retry_state_store"
    | "use_sqlite_state_store";
  reasonMeta?: Record<string, unknown>;
};
export type LegacyBackfillResult =
  | { ok: true; summary: LegacyBackfillSummary }
  | LegacyBackfillFailure;
export type LegacyBackfillRequest = {
  workspaceRootAbs: string;
  projectName: string;
};
export type LegacyBackfillEntry = {
  runId: string;
  planName: string;
  runPath: string;
  generatedAtEpochMs: number;
  status: "ok" | "fail_closed";
  reasonCode: string;
  keyType: "traceId" | "requestId" | "messageId";
  keyValue?: string;
  correlationSessionId?: string;
  window: { startEpochMs?: number; endEpochMs?: number; maxWindowMs: number };
  probeIds: string[];
  nonReconstructible?: boolean;
  missingFields?: string[];
};
export type StateStoreJsonRecord = Record<string, unknown>;
export type RunStateRebuildSource = {
  planName: string;
  runId: string;
  runDirAbs: string;
  runDirPathRel: string;
  execution: StateStoreJsonRecord;
  evidence: StateStoreJsonRecord;
  evidencePresent: boolean;
  files: Array<{
    kind: "context_resolved" | "execution_result" | "evidence" | "correlation";
    pathAbs: string;
  }>;
};
export type RunStateRebuildRequest = {
  workspaceRootAbs: string;
  projectName: string;
  strict?: boolean;
};
export type RunStateStoreFailure = {
  ok: false;
  reasonCode: RunStateStoreFailureCode;
  reason: string;
  nextAction:
    | "rebuild_state_store"
    | "retry_state_store"
    | "correct_state_store_input"
    | "retry_cutover"
    | "run_legacy_backfill"
    | "repair_state_store";
  reasonMeta?: Record<string, unknown>;
};
export type OpenRunStateStore = {
  ok: true;
  projectName: string;
  databasePathAbs: string;
  schemaVersion: number;
  database: RunStateDatabase;
  close(): void;
};
export type RunStateStoreOpenResult = OpenRunStateStore | RunStateStoreFailure;
export type RunStateCutoverStatus = "pre_cutover" | "cutover_in_progress" | "cutover_complete";
export type RunStateCutover = {
  projectName: string;
  status: RunStateCutoverStatus;
  transitionRevision: number;
  updatedAtEpochMs: number;
  completedAtEpochMs?: number;
  reasonCode?: string;
};
export type RunStateCutoverResult =
  | { ok: true; cutover: RunStateCutover; idempotent?: boolean }
  | RunStateStoreFailure;
export type RunStateArtifactLink = {
  artifactKind:
    | "context_resolved"
    | "execution_result"
    | "evidence"
    | "correlation"
    | "execution_orchestration";
  pathRel: string;
  createdAtEpochMs: number;
  planName?: string;
  runId?: string;
  suiteRunId?: string;
  checksum?: string;
};
export type RegressionSuiteCheckpoint = {
  suiteRunId: string;
  executionProfile: string;
  status: "pass" | "fail" | "blocked" | "partial_fail" | "in_progress";
  startedAtEpochMs: number;
  updatedAtEpochMs: number;
  nextPlanOrder?: number;
  activePlanName?: string;
  activePlanOrder?: number;
  activeRunId?: string;
  activePhase?: "trigger" | "watchers" | "external_verification";
  continuation?: Record<string, unknown>;
  completedAtEpochMs?: number;
  reasonCode?: string;
  expectedRevision?: number;
  ownerId?: string;
  leaseExpiresAtEpochMs?: number;
};
export type RegressionPlanRunProjection = {
  planName: string;
  runId: string;
  status: "executed" | "blocked" | "skipped";
  runDirPathRel: string;
  planOrder?: number;
  runStatus?: "pass" | "fail" | "blocked" | "in_progress";
  stepCount?: number;
  failedStepCount?: number;
  startedAtEpochMs?: number;
  completedAtEpochMs?: number;
  reasonCode?: string;
};
export type RunStateCheckpointFailure = {
  ok: false;
  reasonCode:
    | "suite_checkpoint_conflict"
    | "suite_checkpoint_stale_revision"
    | "suite_checkpoint_owner_active"
    | "suite_checkpoint_lease_expired"
    | "suite_checkpoint_invalid"
    | "suite_state_transition_invalid"
    | "run_state_persist_failed";
  reason: string;
  nextAction: "resume_same_suite" | "retry_state_store" | "correct_checkpoint_input";
  reasonMeta?: Record<string, unknown>;
};
export type PersistRegressionSuiteStateResult =
  | { ok: true; revision: number }
  | RunStateCheckpointFailure
  | RunStateStoreFailure;
export type AcquireRegressionSuiteLeaseResult =
  | { ok: true; revision: number; leaseExpiresAtEpochMs: number }
  | RunStateCheckpointFailure;
export type CorrelationObservation = {
  strictLineKey: string;
  sequenceOrder: number;
  selectorPolicy: "exact_instance" | "any_instance" | "all_instances" | "aggregate" | "quorum";
  operator: "exact" | "at_least" | "at_most" | "range";
  expectedHitDelta?: number;
  expectedMinHitDelta?: number;
  expectedMaxHitDelta?: number;
  probeId: string;
  runtimeInstanceId: string;
  baselineHitCount: number;
  currentHitCount: number;
  observedAtEpochMs: number;
  expectedRevision?: number;
};
export type CorrelationObservationResult =
  | {
      ok: true;
      revision: number;
      observedHitDelta: number;
      status: "collecting" | "matched" | "fail_closed";
    }
  | CorrelationPersistenceFailure;
export type CorrelationPersistenceFailure = {
  ok: false;
  reasonCode:
    | "correlation_identity_invalid"
    | "correlation_revision_conflict"
    | "correlation_runtime_instance_changed"
    | "correlation_hit_count_non_monotonic"
    | "correlation_expectation_exceeded"
    | "correlation_persist_failed";
  reason: string;
  nextAction: "correct_correlation_input" | "resume_same_suite" | "retry_state_store";
  reasonMeta?: Record<string, unknown>;
};
export type CorrelationSession = {
  planName: string;
  runId: string;
  correlationSessionId: string;
  keyType: "traceId" | "requestId" | "messageId";
  keyValue?: string;
  maxWindowMs: number;
  startedAtEpochMs: number;
  status: "collecting" | "correlated" | "fail_closed";
  reasonCode: string;
  correlationPathRel?: string;
  expectations?: Array<{
    strictLineKey: string;
    sequenceOrder: number;
    selectorPolicy: CorrelationObservation["selectorPolicy"];
    operator: CorrelationObservation["operator"];
    expectedHitDelta?: number;
    expectedMinHitDelta?: number;
    expectedMaxHitDelta?: number;
    label?: string;
  }>;
};
export type CorrelationSessionResult =
  | { ok: true; revision: number }
  | CorrelationPersistenceFailure;
export type WatcherRunProjection = {
  planName: string;
  runId: string;
  suiteRunId?: string;
  watcherName: string;
  dependencyStepOrder: number;
  watcherIndex: number;
  providerType: string;
  status: "in_progress" | "pass" | "fail_assertion" | "blocked_dependency" | "blocked_runtime";
  outcome: "verified" | "failed_expectation" | "timed_out" | "blocked";
  reasonCode?: string;
  startedAtEpochMs: number;
  deadlineAtEpochMs: number;
  completedAtEpochMs?: number;
  timeoutMs: number;
  pollIntervalMs: number;
  retryMax: number;
  attemptCount: number;
  nextAttemptAtEpochMs?: number;
  lastObservation?: Record<string, unknown>;
  lastAssertion?: Record<string, unknown>;
  continuation?: Record<string, unknown>;
  revision?: number;
  artifactPathRel?: string;
  attempts?: Array<{
    attemptNumber: number;
    observedAtEpochMs: number;
    status: string;
    reasonCode?: string;
    durationMs?: number;
    observationSummary?: Record<string, unknown>;
  }>;
};
export type WatcherPersistenceFailure = {
  ok: false;
  reasonCode:
    | "watcher_checkpoint_conflict"
    | "watcher_checkpoint_stale_revision"
    | "watcher_checkpoint_invalid"
    | "watcher_resume_identity_mismatch"
    | "watcher_deadline_invalid"
    | "watcher_attempt_non_monotonic"
    | "watcher_timeout"
    | "watcher_target_unreachable"
    | "watcher_expectation_failed"
    | "watcher_persist_failed";
  reason: string;
  nextAction: "resume_same_suite" | "retry_state_store" | "correct_watcher_input";
  reasonMeta?: Record<string, unknown>;
};
export type WatcherPersistenceResult = { ok: true; revision: number } | WatcherPersistenceFailure;
export type PersistedRegressionSuiteCheckpoint = {
  suiteRunId: string;
  executionProfile: string;
  status: RegressionSuiteCheckpoint["status"];
  revision: number;
  startedAtEpochMs: number;
  updatedAtEpochMs: number;
  nextPlanOrder?: number;
  activePlanName?: string;
  activePlanOrder?: number;
  activeRunId?: string;
  activePhase?: RegressionSuiteCheckpoint["activePhase"];
  continuation?: Record<string, unknown>;
};

export type ExternalVerificationAssertionProjection = {
  id: string;
  actualPath: string;
  operator: string;
  status: "pass" | "fail" | "blocked";
  expected?: unknown;
  actual?: unknown;
  reasonCode?: string;
};

export type ExternalVerificationProjection = {
  planName: string;
  runId: string;
  suiteRunId?: string;
  verificationName: string;
  verificationOrder: number;
  providerType: "http" | "sql";
  status: "pass" | "fail_assertion" | "blocked_runtime";
  reasonCode?: string;
  durationMs?: number;
  connectionRef?: string;
  requestSummary?: unknown;
  responseSummary?: unknown;
  assertions?: ExternalVerificationAssertionProjection[];
  revision?: number;
  artifactPathRel?: string;
  createdAtEpochMs: number;
  updatedAtEpochMs: number;
};

export type ExternalVerificationPersistenceFailure = {
  ok: false;
  reasonCode:
    | "external_verification_state_invalid"
    | "external_verification_state_conflict"
    | "external_verification_state_stale_revision"
    | "external_verification_state_redaction_failed"
    | "external_verification_state_persist_failed";
  reason: string;
  nextAction:
    | "correct_external_verification_input"
    | "resume_same_suite"
    | "retry_state_store"
    | "rebuild_state_store";
  reasonMeta?: Record<string, unknown>;
};

export type ExternalVerificationPersistenceResult =
  | { ok: true; revision: number }
  | ExternalVerificationPersistenceFailure;
