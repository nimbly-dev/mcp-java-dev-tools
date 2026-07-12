CREATE TABLE correlation_runs (
  correlation_run_pk INTEGER PRIMARY KEY,
  project_name TEXT NOT NULL,
  plan_name TEXT NOT NULL,
  run_id TEXT NOT NULL,
  suite_run_id TEXT,
  correlation_session_id TEXT NOT NULL,
  status TEXT NOT NULL,
  reason_code TEXT NOT NULL,
  expected_line_count INTEGER NOT NULL,
  matched_line_count INTEGER NOT NULL,
  window_start_epoch_ms INTEGER,
  window_end_epoch_ms INTEGER,
  max_window_ms INTEGER NOT NULL,
  started_at_epoch_ms INTEGER NOT NULL,
  correlated_at_epoch_ms INTEGER,
  revision INTEGER NOT NULL DEFAULT 0,
  correlation_path_rel TEXT,
  UNIQUE(project_name, run_id, correlation_session_id)
);

CREATE TABLE correlation_keys (
  correlation_key_pk INTEGER PRIMARY KEY,
  correlation_run_pk INTEGER NOT NULL REFERENCES correlation_runs(correlation_run_pk),
  key_type TEXT NOT NULL,
  key_value_sanitized TEXT,
  key_value_hash TEXT,
  UNIQUE(correlation_run_pk, key_type, key_value_hash)
);

CREATE TABLE correlation_line_expectations (
  line_expectation_pk INTEGER PRIMARY KEY,
  correlation_run_pk INTEGER NOT NULL REFERENCES correlation_runs(correlation_run_pk),
  sequence_order INTEGER NOT NULL,
  label TEXT,
  strict_line_key TEXT NOT NULL,
  selector_policy TEXT NOT NULL,
  operator TEXT NOT NULL,
  expected_hit_delta INTEGER,
  expected_min_hit_delta INTEGER,
  expected_max_hit_delta INTEGER,
  status TEXT NOT NULL,
  reason_code TEXT,
  first_hit_epoch_ms INTEGER,
  last_hit_epoch_ms INTEGER,
  UNIQUE(correlation_run_pk, sequence_order, strict_line_key)
);

CREATE TABLE correlation_probe_observations (
  probe_observation_pk INTEGER PRIMARY KEY,
  line_expectation_pk INTEGER NOT NULL REFERENCES correlation_line_expectations(line_expectation_pk),
  probe_id TEXT NOT NULL,
  logical_service_id TEXT,
  service_instance_id TEXT,
  runtime_instance_id TEXT NOT NULL,
  probe_address_observed TEXT,
  observed_scope_state TEXT,
  scope_state_observed_at_epoch_ms INTEGER,
  scope_state_expires_at_epoch_ms INTEGER,
  baseline_hit_count INTEGER NOT NULL,
  current_hit_count INTEGER NOT NULL,
  observed_hit_delta INTEGER NOT NULL,
  last_hit_epoch_ms INTEGER,
  first_observed_at_epoch_ms INTEGER NOT NULL,
  last_observed_at_epoch_ms INTEGER NOT NULL,
  sample_count INTEGER NOT NULL,
  revision INTEGER NOT NULL DEFAULT 0,
  UNIQUE(line_expectation_pk, probe_id, runtime_instance_id)
);
