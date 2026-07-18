CREATE TABLE runtime_evidence_cursors (
  runtime_evidence_cursor_pk INTEGER PRIMARY KEY,
  project_name TEXT NOT NULL,
  suite_run_id TEXT,
  run_id TEXT NOT NULL,
  correlation_session_id TEXT NOT NULL,
  probe_id TEXT NOT NULL,
  runtime_instance_id TEXT NOT NULL,
  last_sequence INTEGER NOT NULL,
  stream_runtime_instance_id TEXT NOT NULL,
  stream_reset_epoch INTEGER NOT NULL DEFAULT 0,
  latest_observation_at_epoch_ms INTEGER NOT NULL,
  status TEXT NOT NULL,
  reason_code TEXT NOT NULL,
  dedupe_identity TEXT NOT NULL,
  revision INTEGER NOT NULL DEFAULT 0,
  UNIQUE(project_name, run_id, correlation_session_id, probe_id, runtime_instance_id)
);
