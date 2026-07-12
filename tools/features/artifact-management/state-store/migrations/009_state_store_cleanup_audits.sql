CREATE TABLE state_store_cleanup_audits (
  cleanup_audit_pk INTEGER PRIMARY KEY,
  project_name TEXT NOT NULL,
  cleanup_id TEXT NOT NULL UNIQUE,
  started_at_epoch_ms INTEGER NOT NULL,
  completed_at_epoch_ms INTEGER NOT NULL,
  dry_run INTEGER NOT NULL CHECK (dry_run IN (0, 1)),
  terminal_older_than_days INTEGER NOT NULL,
  keep_most_recent_terminal_runs INTEGER NOT NULL,
  max_delete_batch INTEGER NOT NULL,
  outcome TEXT NOT NULL,
  reason_code TEXT,
  scanned_runs INTEGER NOT NULL,
  policy_candidate_runs INTEGER NOT NULL,
  eligible_runs INTEGER NOT NULL,
  deleted_runs INTEGER NOT NULL,
  skipped_active INTEGER NOT NULL,
  skipped_artifact_link INTEGER NOT NULL,
  retained_by_age INTEGER NOT NULL,
  retained_by_count INTEGER NOT NULL,
  remaining_eligible_runs INTEGER NOT NULL,
  reasons_json TEXT NOT NULL
);

CREATE INDEX state_store_cleanup_audits_project_completed_idx
  ON state_store_cleanup_audits(project_name, completed_at_epoch_ms DESC, cleanup_audit_pk DESC);
