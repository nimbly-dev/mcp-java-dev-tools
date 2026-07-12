CREATE TABLE IF NOT EXISTS schema_migrations (
  version INTEGER PRIMARY KEY,
  applied_at_epoch_ms INTEGER NOT NULL,
  migration_name TEXT NOT NULL,
  checksum TEXT NOT NULL
);

CREATE TABLE store_metadata (
  metadata_key TEXT PRIMARY KEY,
  metadata_value TEXT NOT NULL,
  updated_at_epoch_ms INTEGER NOT NULL
);

CREATE TABLE artifacts (
  artifact_id INTEGER PRIMARY KEY,
  project_name TEXT NOT NULL,
  plan_name TEXT,
  run_id TEXT,
  suite_run_id TEXT,
  artifact_kind TEXT NOT NULL,
  path_rel TEXT NOT NULL,
  checksum TEXT,
  created_at_epoch_ms INTEGER NOT NULL,
  UNIQUE(project_name, artifact_kind, path_rel)
);
