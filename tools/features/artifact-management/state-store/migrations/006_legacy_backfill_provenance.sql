CREATE TABLE legacy_backfill_imports (
  legacy_backfill_import_pk INTEGER PRIMARY KEY,
  project_name TEXT NOT NULL,
  source_path_rel TEXT NOT NULL,
  detected_legacy_schema_version INTEGER NOT NULL,
  importer_version TEXT NOT NULL,
  import_started_at_epoch_ms INTEGER NOT NULL,
  import_completed_at_epoch_ms INTEGER,
  inserted_count INTEGER NOT NULL DEFAULT 0,
  skipped_count INTEGER NOT NULL DEFAULT 0,
  conflicting_count INTEGER NOT NULL DEFAULT 0,
  invalid_count INTEGER NOT NULL DEFAULT 0,
  non_reconstructible_count INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL,
  reason_code TEXT,
  UNIQUE(project_name, source_path_rel)
);
