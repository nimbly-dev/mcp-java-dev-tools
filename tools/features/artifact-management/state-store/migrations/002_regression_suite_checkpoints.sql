CREATE TABLE suite_runs (
  suite_run_pk INTEGER PRIMARY KEY,
  project_name TEXT NOT NULL,
  suite_run_id TEXT NOT NULL,
  execution_profile TEXT,
  status TEXT NOT NULL,
  next_plan_order INTEGER,
  active_plan_name TEXT,
  active_plan_order INTEGER,
  active_run_id TEXT,
  active_phase TEXT CHECK(active_phase IN ('trigger', 'watchers', 'external_verification')),
  continuation_json TEXT,
  owner_id TEXT,
  lease_expires_at_epoch_ms INTEGER,
  revision INTEGER NOT NULL DEFAULT 0,
  started_at_epoch_ms INTEGER NOT NULL,
  updated_at_epoch_ms INTEGER NOT NULL,
  completed_at_epoch_ms INTEGER,
  reason_code TEXT,
  UNIQUE(project_name, suite_run_id)
);

CREATE TABLE plan_runs (
  plan_run_pk INTEGER PRIMARY KEY,
  suite_run_pk INTEGER REFERENCES suite_runs(suite_run_pk),
  project_name TEXT NOT NULL,
  plan_name TEXT NOT NULL,
  run_id TEXT NOT NULL,
  plan_order INTEGER,
  status TEXT NOT NULL,
  step_count INTEGER,
  failed_step_count INTEGER,
  started_at_epoch_ms INTEGER,
  completed_at_epoch_ms INTEGER,
  revision INTEGER NOT NULL DEFAULT 0,
  reason_code TEXT,
  run_dir_path_rel TEXT NOT NULL,
  UNIQUE(project_name, plan_name, run_id)
);
