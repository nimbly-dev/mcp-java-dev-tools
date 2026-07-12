CREATE TABLE external_verifications (
  external_verification_pk INTEGER PRIMARY KEY,
  plan_run_pk INTEGER NOT NULL REFERENCES plan_runs(plan_run_pk),
  project_name TEXT NOT NULL,
  plan_name TEXT NOT NULL,
  run_id TEXT NOT NULL,
  suite_run_id TEXT,
  verification_name TEXT NOT NULL,
  verification_order INTEGER NOT NULL,
  provider_type TEXT NOT NULL,
  status TEXT NOT NULL,
  reason_code TEXT,
  duration_ms INTEGER,
  connection_ref TEXT,
  request_summary_json TEXT,
  response_summary_json TEXT,
  assertion_pass_count INTEGER NOT NULL DEFAULT 0,
  assertion_fail_count INTEGER NOT NULL DEFAULT 0,
  assertion_blocked_count INTEGER NOT NULL DEFAULT 0,
  revision INTEGER NOT NULL DEFAULT 0,
  artifact_path_rel TEXT,
  created_at_epoch_ms INTEGER NOT NULL,
  updated_at_epoch_ms INTEGER NOT NULL,
  UNIQUE(project_name, plan_name, run_id, verification_name)
);

CREATE TABLE external_verification_assertions (
  external_verification_assertion_pk INTEGER PRIMARY KEY,
  external_verification_pk INTEGER NOT NULL REFERENCES external_verifications(external_verification_pk),
  assertion_id TEXT NOT NULL,
  actual_path TEXT NOT NULL,
  operator TEXT NOT NULL,
  status TEXT NOT NULL,
  expected_summary_text TEXT,
  actual_summary_text TEXT,
  reason_code TEXT,
  UNIQUE(external_verification_pk, assertion_id)
);
